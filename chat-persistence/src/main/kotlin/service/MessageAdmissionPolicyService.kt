package com.chat.persistence.service

import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.model.MemberRole
import com.chat.persistence.config.ChatRedisProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Clock

interface MessageAdmissionPolicyService {
    fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole = MemberRole.MEMBER)

    object Noop : MessageAdmissionPolicyService {
        override fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole) = Unit
    }
}

data class RoomAdmissionPolicy(
    val roomRateLimitPerSecond: Int? = null,
    val userRateLimitPerSecond: Int? = null,
    val slowModeSeconds: Int? = null,
    val moderatorPriority: Boolean = true,
) {
    fun hasLimit(): Boolean {
        return positive(roomRateLimitPerSecond) ||
            positive(userRateLimitPerSecond) ||
            positive(slowModeSeconds)
    }

    private fun positive(value: Int?): Boolean = value != null && value > 0
}

interface RoomAdmissionPolicyReader {
    fun admissionPolicy(roomId: Long): RoomAdmissionPolicy
}

@Service
class RedisMessageAdmissionPolicyService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
    private val roomAdmissionPolicyReader: RoomAdmissionPolicyReader,
    private val clock: Clock,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : MessageAdmissionPolicyService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val script: RedisScript<Long> = DefaultRedisScript(
        ADMISSION_SCRIPT,
        Long::class.javaObjectType,
    )

    override fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole) {
        val policy = roomAdmissionPolicyReader.admissionPolicy(roomId)
        if (policy.moderatorPriority && memberRole.priorityBypassesAdmission()) {
            return
        }

        if (!policy.hasLimit()) {
            return
        }

        val epochSecond = clock.instant().epochSecond
        val roomRateLimit = policy.roomRateLimitPerSecond.positiveOrZero()
        val userRateLimit = policy.userRateLimitPerSecond.positiveOrZero()
        val slowModeMillis = policy.slowModeSeconds.positiveOrZero() * MILLIS_PER_SECOND
        val keys = listOf(
            roomRateKey(roomId, epochSecond),
            userRateKey(roomId, senderId, epochSecond),
            slowModeKey(roomId, senderId),
        )

        val result = try {
            redisTemplate.execute(
                script,
                keys,
                redisProperties.admission.rateLimitWindowTtl.toMillis().toString(),
                roomRateLimit.toString(),
                userRateLimit.toString(),
                slowModeMillis.toString(),
                senderId.toString(),
            )
        } catch (e: Exception) {
            recordRejected(REASON_REDIS_ERROR)
            logger.warn("Failed to evaluate message admission policy roomId={} senderId={}", roomId, senderId, e)
            throw MessageAdmissionRejectedException("message admission policy unavailable", e)
        }

        when (result) {
            RESULT_ALLOWED -> return
            RESULT_ROOM_RATE_LIMITED -> reject("room rate limit exceeded", REASON_ROOM_RATE_LIMITED)
            RESULT_USER_RATE_LIMITED -> reject("user rate limit exceeded", REASON_USER_RATE_LIMITED)
            RESULT_SLOW_MODE_ACTIVE -> reject("slow mode active", REASON_SLOW_MODE_ACTIVE)
            else -> {
                recordRejected(REASON_SCRIPT_ERROR)
                throw MessageAdmissionRejectedException("message admission policy unavailable")
            }
        }
    }

    private fun reject(message: String, reason: String): Nothing {
        recordRejected(reason)
        throw MessageAdmissionRejectedException(message)
    }

    private fun recordRejected(reason: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.message.admission.rejected")
                .tag("reason", reason)
                .register(registry)
                .increment()
        }
    }

    private fun roomRateKey(roomId: Long, epochSecond: Long): String {
        return "${redisProperties.admission.keyPrefix}{$roomId}:rate:room:$epochSecond"
    }

    private fun userRateKey(roomId: Long, senderId: Long, epochSecond: Long): String {
        return "${redisProperties.admission.keyPrefix}{$roomId}:rate:user:$senderId:$epochSecond"
    }

    private fun slowModeKey(roomId: Long, senderId: Long): String {
        return "${redisProperties.admission.keyPrefix}{$roomId}:slow:user:$senderId"
    }

    private fun Int?.positiveOrZero(): Int = this?.takeIf { it > 0 } ?: 0

    private fun MemberRole.priorityBypassesAdmission(): Boolean {
        return this == MemberRole.OWNER || this == MemberRole.ADMIN
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
        const val RESULT_ALLOWED = 0L
        const val RESULT_ROOM_RATE_LIMITED = 1L
        const val RESULT_USER_RATE_LIMITED = 2L
        const val RESULT_SLOW_MODE_ACTIVE = 3L
        const val REASON_ROOM_RATE_LIMITED = "room_rate_limited"
        const val REASON_USER_RATE_LIMITED = "user_rate_limited"
        const val REASON_SLOW_MODE_ACTIVE = "slow_mode_active"
        const val REASON_REDIS_ERROR = "redis_error"
        const val REASON_SCRIPT_ERROR = "script_error"
        const val ADMISSION_SCRIPT = """
            local windowMillis = tonumber(ARGV[1])
            local roomLimit = tonumber(ARGV[2])
            local userLimit = tonumber(ARGV[3])
            local slowModeMillis = tonumber(ARGV[4])
            local slowModeValue = ARGV[5]

            if slowModeMillis > 0 and redis.call('EXISTS', KEYS[3]) == 1 then
              return 3
            end

            if roomLimit > 0 then
              local currentRoomCount = tonumber(redis.call('GET', KEYS[1]) or '0')
              if currentRoomCount >= roomLimit then
                return 1
              end
            end

            if userLimit > 0 then
              local currentUserCount = tonumber(redis.call('GET', KEYS[2]) or '0')
              if currentUserCount >= userLimit then
                return 2
              end
            end

            if roomLimit > 0 then
              local roomCount = redis.call('INCR', KEYS[1])
              local roomTtl = redis.call('PTTL', KEYS[1])
              if roomCount == 1 or roomTtl == -1 then
                redis.call('PEXPIRE', KEYS[1], windowMillis)
              end
            end

            if userLimit > 0 then
              local userCount = redis.call('INCR', KEYS[2])
              local userTtl = redis.call('PTTL', KEYS[2])
              if userCount == 1 or userTtl == -1 then
                redis.call('PEXPIRE', KEYS[2], windowMillis)
              end
            end

            if slowModeMillis > 0 then
              redis.call('SET', KEYS[3], slowModeValue, 'PX', slowModeMillis)
            end

            return 0
        """
    }
}
