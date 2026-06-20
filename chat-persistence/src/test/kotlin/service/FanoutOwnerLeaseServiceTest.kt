package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamKeyResolver
import com.chat.persistence.config.ChatRedisProperties
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.stream.Stream

class FanoutOwnerLeaseServiceTest {

    @Suppress("UNCHECKED_CAST")
    private val redisScriptMatcherPlaceholder = mock(RedisScript::class.java) as RedisScript<Long>

    @Test
    fun `fanout owner lease 기본값은 production 기준 10초 TTL과 3초 renew interval이다`() {
        val ownerLease = ChatWorkerProperties().fanout.ownerLease

        assertTrue(ownerLease.enabled)
        assertEquals(10_000, ownerLease.ttlMillis)
        assertEquals(3_000, ownerLease.renewIntervalMillis)
        assertEquals("chat:fanout:owner:room:", ownerLease.keyPrefix)
    }

    @Test
    fun `room stream key parser는 roomId와 streamShard를 추출한다`() {
        val keyResolver = MessageStreamKeyResolver(ChatRedisProperties())

        val parsed = keyResolver.parseRoomStreamKey("chat:stream:room:10:shard:2")

        assertNotNull(parsed)
        assertEquals(10L, parsed?.roomId)
        assertEquals(2, parsed?.streamShard)
    }

    @Test
    fun `owner lease 획득은 Redis SET NX PX에 worker token과 10초 TTL을 저장한다`() {
        val redis = redisTemplate()
        val service = leaseService(redis.template)

        val lease = service.acquire(roomId = 10L, streamShard = 0)

        assertNotNull(lease)
        assertEquals("chat:fanout:owner:room:10:shard:0", lease?.key)
        assertTrue(lease?.value?.startsWith("worker-1:") == true)
        assertEquals(
            listOf(SetIfAbsentCall("chat:fanout:owner:room:10:shard:0", lease!!.value, Duration.ofMillis(10_000))),
            redis.setIfAbsentCalls,
        )
    }

    @Test
    fun `이미 owner가 있으면 lease 획득에 실패하고 null을 반환한다`() {
        val redis = redisTemplate()
        redis.storage["chat:fanout:owner:room:10:shard:0"] = "worker-other:token"
        val service = leaseService(redis.template)

        val lease = service.acquire(roomId = 10L, streamShard = 0)

        assertEquals(null, lease)
    }

    @Test
    fun `같은 worker의 기존 lease는 renew interval 이후 token 비교 Lua script로 renew한다`() {
        val redis = redisTemplate()
        val clock = MutableClock(Instant.parse("2026-06-19T00:00:00Z"))
        val service = leaseService(redis.template, clock = clock)
        val firstLease = service.acquire(roomId = 10L, streamShard = 0)
            ?: throw AssertionError("first lease should be acquired")
        clock.advance(Duration.ofMillis(3_000))

        val renewedLease = service.acquire(roomId = 10L, streamShard = 0)

        assertEquals(firstLease, renewedLease)
        assertEquals(
            listOf(ScriptCall("chat:fanout:owner:room:10:shard:0", listOf(firstLease.value, "10000"))),
            redis.scriptCalls,
        )
    }

    @Test
    fun `기존 owner lease는 renew interval이 지난 경우에만 Redis TTL을 갱신한다`() {
        val redis = redisTemplate()
        val clock = MutableClock(Instant.parse("2026-06-19T00:00:00Z"))
        val service = leaseService(redis.template, clock = clock)
        val firstLease = service.acquire(roomId = 10L, streamShard = 0)
            ?: throw AssertionError("first lease should be acquired")

        val beforeIntervalLease = service.acquire(roomId = 10L, streamShard = 0)
        clock.advance(Duration.ofMillis(2_999))
        val stillBeforeIntervalLease = service.acquire(roomId = 10L, streamShard = 0)
        clock.advance(Duration.ofMillis(1))
        val afterIntervalLease = service.acquire(roomId = 10L, streamShard = 0)

        assertEquals(firstLease, beforeIntervalLease)
        assertEquals(firstLease, stillBeforeIntervalLease)
        assertEquals(firstLease, afterIntervalLease)
        assertEquals(
            listOf(ScriptCall("chat:fanout:owner:room:10:shard:0", listOf(firstLease.value, "10000"))),
            redis.scriptCalls,
        )
    }

    @Test
    fun `renew 중 Redis 일시 오류가 발생하면 기존 owner token을 로컬에 유지한다`() {
        val redis = redisTemplate()
        val clock = MutableClock(Instant.parse("2026-06-19T00:00:00Z"))
        val service = leaseService(redis.template, clock = clock)
        val firstLease = service.acquire(roomId = 10L, streamShard = 0)
            ?: throw AssertionError("first lease should be acquired")
        redis.scriptFailures += true
        clock.advance(Duration.ofMillis(3_000))

        val leaseDuringRedisError = service.acquire(roomId = 10L, streamShard = 0)
        val leaseAfterRecovery = service.acquire(roomId = 10L, streamShard = 0)

        assertEquals(firstLease, leaseDuringRedisError)
        assertEquals(firstLease, leaseAfterRecovery)
        assertEquals(listOf(SetIfAbsentCall("chat:fanout:owner:room:10:shard:0", firstLease.value, Duration.ofMillis(10_000))), redis.setIfAbsentCalls)
        assertEquals(
            listOf(
                ScriptCall("chat:fanout:owner:room:10:shard:0", listOf(firstLease.value, "10000")),
                ScriptCall("chat:fanout:owner:room:10:shard:0", listOf(firstLease.value, "10000")),
            ),
            redis.scriptCalls,
        )
    }

    @Test
    fun `validate 중 Redis 일시 오류가 발생해도 기존 owner token으로 renew를 재시도한다`() {
        val redis = redisTemplate()
        val clock = MutableClock(Instant.parse("2026-06-19T00:00:00Z"))
        val service = leaseService(redis.template, clock = clock)
        val lease = service.acquire(roomId = 10L, streamShard = 0)
            ?: throw AssertionError("lease should be acquired")
        redis.getFailures += true

        assertFalse(service.validate(lease, FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH))
        clock.advance(Duration.ofMillis(3_000))

        val renewedLease = service.acquire(roomId = 10L, streamShard = 0)

        assertEquals(lease, renewedLease)
        assertEquals(
            listOf(ScriptCall("chat:fanout:owner:room:10:shard:0", listOf(lease.value, "10000"))),
            redis.scriptCalls,
        )
    }

    @Test
    fun `publish 전 token 검증 실패는 false를 반환하고 metric을 기록한다`() {
        val redis = redisTemplate()
        val meterRegistry = SimpleMeterRegistry()
        val service = leaseService(redis.template, meterRegistry = meterRegistry)
        val lease = service.acquire(roomId = 10L, streamShard = 0)
            ?: throw AssertionError("lease should be acquired")
        redis.storage[lease.key] = "worker-other:token"

        assertFalse(service.validate(lease, FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH))
        assertEquals(
            1.0,
            meterRegistry.find("chat.fanout.owner.token_mismatch")
                .tag("stage", "before_publish")
                .counter()
                ?.count(),
        )
    }

    @Test
    fun `owner lease disabled 상태에서는 Redis를 호출하지 않고 fanout을 허용한다`() {
        val redis = redisTemplate()
        val service = leaseService(
            redis = redis.template,
            workerProperties = ChatWorkerProperties(
                fanout = ChatWorkerProperties.StreamConsumer(
                    consumerGroup = "fanout",
                    ownerLease = ChatWorkerProperties.FanoutOwnerLease(enabled = false),
                ),
            ),
        )

        val lease = service.acquire(roomId = 10L, streamShard = 0)

        assertNotNull(lease)
        assertFalse(lease?.enabled == true)
        assertTrue(service.validate(lease!!, FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH))
        assertEquals(emptyList<SetIfAbsentCall>(), redis.setIfAbsentCalls)
        assertEquals(emptyList<ScriptCall>(), redis.scriptCalls)
    }

    private fun leaseService(
        redis: RedisTemplate<String, String>,
        workerProperties: ChatWorkerProperties = ChatWorkerProperties(consumerName = "worker-1"),
        meterRegistry: MeterRegistry? = null,
        clock: Clock = Clock.systemUTC(),
    ): RedisFanoutOwnerLeaseService {
        return RedisFanoutOwnerLeaseService(
            redisTemplate = redis,
            workerProperties = workerProperties,
            meterRegistryProvider = meterRegistry?.let { meterRegistryProvider(it) },
            clock = clock,
        )
    }

    private fun meterRegistryProvider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry = meterRegistry
            override fun getObject(vararg args: Any?): MeterRegistry = meterRegistry
            override fun getIfAvailable(): MeterRegistry = meterRegistry
            override fun getIfUnique(): MeterRegistry = meterRegistry
            override fun iterator(): MutableIterator<MeterRegistry> = mutableListOf(meterRegistry).iterator()
            override fun stream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
            override fun orderedStream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisFixture {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        val storage = linkedMapOf<String, String>()
        val setIfAbsentCalls = mutableListOf<SetIfAbsentCall>()
        val scriptCalls = mutableListOf<ScriptCall>()
        val scriptFailures = ArrayDeque<Boolean>()
        val getFailures = ArrayDeque<Boolean>()

        `when`(template.opsForValue()).thenReturn(valueOperations)
        doAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as String
            val ttl = invocation.arguments[2] as Duration
            setIfAbsentCalls += SetIfAbsentCall(key, value, ttl)
            if (storage.containsKey(key)) {
                false
            } else {
                storage[key] = value
                true
            }
        }.`when`(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration::class.java))
        doAnswer { invocation ->
            if (getFailures.removeFirstOrNull() == true) {
                throw IllegalStateException("redis get unavailable")
            }
            storage[invocation.arguments[0] as String]
        }.`when`(valueOperations).get(anyString())
        doAnswer { invocation ->
            val keys = invocation.arguments[1] as List<*>
            val key = keys.single() as String
            val args = invocation.arguments.drop(2).map { it as String }
            scriptCalls += ScriptCall(key, args)
            if (scriptFailures.removeFirstOrNull() == true) {
                throw IllegalStateException("redis script unavailable")
            }
            if (storage[key] == args.first()) {
                1L
            } else {
                0L
            }
        }.`when`(template).execute(anyRedisScript(), anyList<String>(), anyString(), anyString())

        return RedisFixture(
            template = template,
            storage = storage,
            setIfAbsentCalls = setIfAbsentCalls,
            scriptCalls = scriptCalls,
            scriptFailures = scriptFailures,
            getFailures = getFailures,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyRedisScript(): RedisScript<Long> {
        any<RedisScript<Long>>()
        return redisScriptMatcherPlaceholder
    }

    private data class RedisFixture(
        val template: RedisTemplate<String, String>,
        val storage: MutableMap<String, String>,
        val setIfAbsentCalls: List<SetIfAbsentCall>,
        val scriptCalls: List<ScriptCall>,
        val scriptFailures: ArrayDeque<Boolean>,
        val getFailures: ArrayDeque<Boolean>,
    )

    private data class SetIfAbsentCall(
        val key: String,
        val value: String,
        val ttl: Duration,
    )

    private data class ScriptCall(
        val key: String,
        val args: List<String>,
    )

    private class MutableClock(
        private var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }
}
