package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface FanoutOwnerLeaseService {
    fun acquire(roomId: Long, streamShard: Int): FanoutOwnerLease?

    fun validate(
        lease: FanoutOwnerLease,
        stage: FanoutOwnerLeaseValidationStage,
    ): Boolean

    fun release(lease: FanoutOwnerLease)

    companion object {
        val Noop: FanoutOwnerLeaseService = object : FanoutOwnerLeaseService {
            override fun acquire(roomId: Long, streamShard: Int): FanoutOwnerLease {
                return FanoutOwnerLease.disabled(roomId = roomId, streamShard = streamShard)
            }

            override fun validate(
                lease: FanoutOwnerLease,
                stage: FanoutOwnerLeaseValidationStage,
            ): Boolean = true

            override fun release(lease: FanoutOwnerLease) = Unit
        }
    }
}

data class FanoutOwnerLease(
    val key: String,
    val value: String,
    val roomId: Long,
    val streamShard: Int,
    val enabled: Boolean = true,
) {
    companion object {
        fun disabled(roomId: Long, streamShard: Int): FanoutOwnerLease {
            return FanoutOwnerLease(
                key = "disabled:$roomId:$streamShard",
                value = "disabled",
                roomId = roomId,
                streamShard = streamShard,
                enabled = false,
            )
        }
    }
}

enum class FanoutOwnerLeaseValidationStage(val metricTag: String) {
    BEFORE_PUBLISH("before_publish"),
    BEFORE_ACK("before_ack"),
}

@Service
class RedisFanoutOwnerLeaseService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val workerProperties: ChatWorkerProperties,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
    private val clock: Clock = Clock.systemUTC(),
) : FanoutOwnerLeaseService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ownedLeases = ConcurrentHashMap<String, OwnedFanoutOwnerLease>()
    private val renewScript: RedisScript<Long> = DefaultRedisScript(RENEW_SCRIPT, Long::class.javaObjectType)
    private val releaseScript: RedisScript<Long> = DefaultRedisScript(RELEASE_SCRIPT, Long::class.javaObjectType)

    init {
        meterRegistryProvider?.ifAvailable { registry ->
            Gauge.builder("chat.fanout.owner.rooms", ownedLeases) { leases -> leases.size.toDouble() }
                .tag("workerRole", "fanout")
                .tag("roomHeat", "unknown")
                .register(registry)
        }
    }

    override fun acquire(roomId: Long, streamShard: Int): FanoutOwnerLease? {
        val properties = workerProperties.fanout.ownerLease
        if (!properties.enabled) {
            return FanoutOwnerLease.disabled(roomId = roomId, streamShard = streamShard)
        }

        val key = leaseKey(roomId = roomId, streamShard = streamShard)
        val existingLease = ownedLeases[key]
        var lostBeforeAcquire = false
        if (existingLease != null) {
            if (!isRenewDue(existingLease)) {
                return existingLease.lease
            }
            when (renew(existingLease.lease)) {
                true -> {
                    existingLease.lastRenewedAtMillis = nowMillis()
                    return existingLease.lease
                }
                false -> {
                    ownedLeases.remove(key, existingLease)
                    lostBeforeAcquire = true
                }
                null -> return existingLease.lease
            }
        }

        val lease = FanoutOwnerLease(
            key = key,
            value = "${workerProperties.consumerName}:${UUID.randomUUID()}",
            roomId = roomId,
            streamShard = streamShard,
        )

        return try {
            val acquired = redisTemplate.opsForValue().setIfAbsent(
                key,
                lease.value,
                Duration.ofMillis(properties.ttlMillis),
            ) == true
            if (!acquired) {
                recordAcquire(outcome = OUTCOME_FAILURE, reason = "held_by_other")
                return null
            }

            ownedLeases[key] = OwnedFanoutOwnerLease(
                lease = lease,
                lastRenewedAtMillis = nowMillis(),
            )
            recordAcquire(
                outcome = OUTCOME_SUCCESS,
                reason = if (lostBeforeAcquire) "after_lost" else "new",
            )
            if (lostBeforeAcquire) {
                recordTakeover(reason = "ttl_expired")
            }
            lease
        } catch (e: Exception) {
            recordAcquire(outcome = OUTCOME_FAILURE, reason = REASON_REDIS_ERROR)
            logger.warn("Failed to acquire fanout owner lease $key", e)
            null
        }
    }

    override fun validate(
        lease: FanoutOwnerLease,
        stage: FanoutOwnerLeaseValidationStage,
    ): Boolean {
        if (!lease.enabled) {
            return true
        }

        val currentValue = try {
            redisTemplate.opsForValue().get(lease.key)
        } catch (e: Exception) {
            recordLost(REASON_REDIS_ERROR)
            logger.warn("Failed to validate fanout owner lease ${lease.key}", e)
            return false
        }

        if (currentValue == lease.value) {
            return true
        }

        ownedLeases.remove(lease.key)
        recordTokenMismatch(stage)
        recordLost(if (currentValue == null) REASON_EXPIRED else REASON_TOKEN_MISMATCH)
        return false
    }

    override fun release(lease: FanoutOwnerLease) {
        if (!lease.enabled) {
            return
        }

        try {
            redisTemplate.execute(
                releaseScript,
                listOf(lease.key),
                lease.value,
            )
        } catch (e: Exception) {
            logger.warn("Failed to release fanout owner lease ${lease.key}", e)
        } finally {
            ownedLeases.remove(lease.key)
        }
    }

    @PreDestroy
    fun releaseOwnedLeases() {
        ownedLeases.values.toList().forEach { release(it.lease) }
    }

    private fun renew(lease: FanoutOwnerLease): Boolean? {
        val ttlMillis = workerProperties.fanout.ownerLease.ttlMillis.toString()
        val renewed = try {
            redisTemplate.execute(
                renewScript,
                listOf(lease.key),
                lease.value,
                ttlMillis,
            ) == SCRIPT_SUCCESS
        } catch (e: Exception) {
            recordRenew(outcome = OUTCOME_FAILURE)
            recordLost(REASON_REDIS_ERROR)
            logger.warn("Failed to renew fanout owner lease ${lease.key}", e)
            return null
        }

        if (renewed) {
            recordRenew(outcome = OUTCOME_SUCCESS)
            return true
        }

        val currentValue = try {
            redisTemplate.opsForValue().get(lease.key)
        } catch (e: Exception) {
            null
        }
        recordRenew(outcome = OUTCOME_LOST)
        recordLost(if (currentValue == null) REASON_EXPIRED else REASON_TOKEN_MISMATCH)
        return false
    }

    private fun leaseKey(roomId: Long, streamShard: Int): String {
        return "${workerProperties.fanout.ownerLease.keyPrefix}$roomId:shard:$streamShard"
    }

    private fun nowMillis(): Long = clock.millis()

    private fun isRenewDue(ownedLease: OwnedFanoutOwnerLease): Boolean {
        val renewIntervalMillis = workerProperties.fanout.ownerLease.renewIntervalMillis.coerceAtLeast(1)
        return nowMillis() - ownedLease.lastRenewedAtMillis >= renewIntervalMillis
    }

    private fun recordAcquire(outcome: String, reason: String) {
        counter("chat.fanout.owner.lease.acquire") {
            tag("outcome", outcome)
            tag("reason", reason)
        }
    }

    private fun recordRenew(outcome: String) {
        counter("chat.fanout.owner.lease.renew") {
            tag("outcome", outcome)
        }
    }

    private fun recordLost(reason: String) {
        counter("chat.fanout.owner.lease.lost") {
            tag("reason", reason)
        }
    }

    private fun recordTakeover(reason: String) {
        counter("chat.fanout.owner.takeovers") {
            tag("reason", reason)
        }
    }

    private fun recordTokenMismatch(stage: FanoutOwnerLeaseValidationStage) {
        counter("chat.fanout.owner.token_mismatch") {
            tag("stage", stage.metricTag)
        }
    }

    private fun counter(name: String, tags: Counter.Builder.() -> Unit) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder(name)
                .apply(tags)
                .register(registry)
                .increment()
        }
    }

    private data class OwnedFanoutOwnerLease(
        val lease: FanoutOwnerLease,
        var lastRenewedAtMillis: Long,
    )

    private companion object {
        const val SCRIPT_SUCCESS = 1L
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_LOST = "lost"
        const val REASON_EXPIRED = "expired"
        const val REASON_REDIS_ERROR = "redis_error"
        const val REASON_TOKEN_MISMATCH = "token_mismatch"

        const val RENEW_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
        """

        const val RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
        """
    }
}
