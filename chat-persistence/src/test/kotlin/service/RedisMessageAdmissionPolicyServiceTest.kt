package com.chat.persistence.service

import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.model.MemberRole
import com.chat.persistence.config.ChatRedisProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.stream.Stream

class RedisMessageAdmissionPolicyServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC)

    @Suppress("UNCHECKED_CAST")
    private val redisScriptMatcherPlaceholder = mock(RedisScript::class.java) as RedisScript<Long>

    @Test
    fun `방 초당 rate limit 초과는 메시지 수락을 거부하고 stream 이전에 실패시킬 수 있다`() {
        val redis = redisTemplate()
        val policy = RoomAdmissionPolicy(roomRateLimitPerSecond = 1)
        val service = admissionService(redis.template, policy)
        val roomRateKey = "chat:admission:room:{3}:rate:room:${clock.instant().epochSecond}"
        val userRateKey = "chat:admission:room:{3}:rate:user:7:${clock.instant().epochSecond}"
        val slowModeKey = "chat:admission:room:{3}:slow:user:7"
        redis.scriptResults[roomRateKey] = ArrayDeque(listOf(0L, 1L))

        service.requireAllowed(roomId = 3L, senderId = 7L)
        val exception = assertThrows(MessageAdmissionRejectedException::class.java) {
            service.requireAllowed(roomId = 3L, senderId = 7L)
        }

        assertEquals("room rate limit exceeded", exception.message)
        verify(redis.template, times(2)).execute(
            anyRedisScript(),
            eq(listOf(roomRateKey, userRateKey, slowModeKey)),
            eq("2000"),
            eq("1"),
            eq("0"),
            eq("0"),
            eq("7"),
        )
    }

    @Test
    fun `사용자별 초당 rate limit 초과는 메시지 수락을 거부한다`() {
        val redis = redisTemplate()
        val policy = RoomAdmissionPolicy(userRateLimitPerSecond = 1)
        val service = admissionService(redis.template, policy)
        redis.scriptResults["chat:admission:room:{3}:rate:room:${clock.instant().epochSecond}"] = ArrayDeque(listOf(2L))

        val exception = assertThrows(MessageAdmissionRejectedException::class.java) {
            service.requireAllowed(roomId = 3L, senderId = 7L)
        }

        assertEquals("user rate limit exceeded", exception.message)
    }

    @Test
    fun `slow mode TTL 안의 재전송은 메시지 수락을 거부한다`() {
        val redis = redisTemplate()
        val policy = RoomAdmissionPolicy(slowModeSeconds = 5)
        val service = admissionService(redis.template, policy)
        redis.scriptResults["chat:admission:room:{3}:rate:room:${clock.instant().epochSecond}"] = ArrayDeque(listOf(3L))

        val exception = assertThrows(MessageAdmissionRejectedException::class.java) {
            service.requireAllowed(roomId = 3L, senderId = 7L)
        }

        assertEquals("slow mode active", exception.message)
    }

    @Test
    fun `정책이 비어 있으면 Redis를 호출하지 않고 허용한다`() {
        val redis = redisTemplate()
        val service = admissionService(redis.template, RoomAdmissionPolicy())

        service.requireAllowed(roomId = 3L, senderId = 7L)

        verify(redis.template, org.mockito.Mockito.never()).execute(
            anyRedisScript(),
            anyList<String>(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
        )
    }

    @Test
    fun `관리자 역할은 room admission rate limit과 slow mode를 우회한다`() {
        val redis = redisTemplate()
        val service = admissionService(
            redis.template,
            RoomAdmissionPolicy(
                roomRateLimitPerSecond = 1,
                userRateLimitPerSecond = 1,
                slowModeSeconds = 5,
            ),
        )

        service.requireAllowed(roomId = 3L, senderId = 7L, memberRole = MemberRole.ADMIN)

        verify(redis.template, org.mockito.Mockito.never()).execute(
            anyRedisScript(),
            anyList<String>(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
        )
    }

    @Test
    fun `moderator priority가 꺼진 방에서는 관리자 역할도 admission 제한 대상이다`() {
        val redis = redisTemplate()
        val service = admissionService(
            redis.template,
            RoomAdmissionPolicy(
                roomRateLimitPerSecond = 1,
                moderatorPriority = false,
            ),
        )
        val roomRateKey = "chat:admission:room:{3}:rate:room:${clock.instant().epochSecond}"
        redis.scriptResults[roomRateKey] = ArrayDeque(listOf(1L))

        val exception = assertThrows(MessageAdmissionRejectedException::class.java) {
            service.requireAllowed(roomId = 3L, senderId = 7L, memberRole = MemberRole.ADMIN)
        }

        assertEquals("room rate limit exceeded", exception.message)
    }

    @Test
    fun `Redis script 실패는 fail closed로 거부하고 metric을 기록한다`() {
        val redis = redisTemplate()
        val meterRegistry = SimpleMeterRegistry()
        doAnswer {
            throw IllegalStateException("redis unavailable")
        }.`when`(redis.template).execute(
            anyRedisScript(),
            anyList<String>(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
        )
        val service = admissionService(
            redis = redis.template,
            policy = RoomAdmissionPolicy(roomRateLimitPerSecond = 1),
            meterRegistry = meterRegistry,
        )

        val exception = assertThrows(MessageAdmissionRejectedException::class.java) {
            service.requireAllowed(roomId = 3L, senderId = 7L)
        }

        assertEquals("message admission policy unavailable", exception.message)
        assertEquals(
            1.0,
            meterRegistry.find("chat.message.admission.rejected")
                .tag("reason", "redis_error")
                .counter()
                ?.count(),
        )
    }

    private fun admissionService(
        redis: RedisTemplate<String, String>,
        policy: RoomAdmissionPolicy,
        meterRegistry: SimpleMeterRegistry? = null,
    ): RedisMessageAdmissionPolicyService {
        return RedisMessageAdmissionPolicyService(
            redisTemplate = redis,
            redisProperties = ChatRedisProperties(),
            roomAdmissionPolicyReader = StaticRoomAdmissionPolicyReader(policy),
            clock = clock,
            meterRegistryProvider = meterRegistry?.let { meterRegistryProvider(it) },
        )
    }

    private class StaticRoomAdmissionPolicyReader(
        private val policy: RoomAdmissionPolicy,
    ) : RoomAdmissionPolicyReader {

        override fun admissionPolicy(roomId: Long): RoomAdmissionPolicy {
            return policy
        }
    }

    private fun meterRegistryProvider(
        meterRegistry: SimpleMeterRegistry,
    ): ObjectProvider<io.micrometer.core.instrument.MeterRegistry> {
        return object : ObjectProvider<io.micrometer.core.instrument.MeterRegistry> {
            override fun getObject(): io.micrometer.core.instrument.MeterRegistry = meterRegistry

            override fun getObject(vararg args: Any?): io.micrometer.core.instrument.MeterRegistry = meterRegistry

            override fun getIfAvailable(): io.micrometer.core.instrument.MeterRegistry = meterRegistry

            override fun getIfUnique(): io.micrometer.core.instrument.MeterRegistry = meterRegistry

            override fun iterator(): MutableIterator<io.micrometer.core.instrument.MeterRegistry> {
                return mutableListOf<io.micrometer.core.instrument.MeterRegistry>(meterRegistry).iterator()
            }

            override fun stream(): Stream<io.micrometer.core.instrument.MeterRegistry> {
                return Stream.of(meterRegistry)
            }

            override fun orderedStream(): Stream<io.micrometer.core.instrument.MeterRegistry> {
                return Stream.of(meterRegistry)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisFixture {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val scriptResults = mutableMapOf<String, ArrayDeque<Long>>()
        doAnswer { invocation ->
            val keys = invocation.arguments[1] as List<*>
            val key = keys.first() as String
            scriptResults[key]?.removeFirstOrNull() ?: 0L
        }.`when`(template).execute(
            anyRedisScript(),
            anyList<String>(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
        )

        return RedisFixture(template, scriptResults)
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyRedisScript(): RedisScript<Long> {
        any<RedisScript<Long>>()
        return redisScriptMatcherPlaceholder
    }

    private data class RedisFixture(
        val template: RedisTemplate<String, String>,
        val scriptResults: MutableMap<String, ArrayDeque<Long>>,
    )
}
