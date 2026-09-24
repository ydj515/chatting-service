package com.chat.persistence.service

import com.chat.core.message.policy.AdmissionPolicy
import com.chat.core.message.port.AdmissionDecision
import com.chat.persistence.config.ChatRedisProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "CHAT_TEST_REDIS_PORT", matches = ".+")
class RedisMessageRateLimiterIntegrationTest {
    @Test
    fun `room limit is shared across senders in the same time window`() {
        Fixture().use { fixture ->
            val policy = AdmissionPolicy(roomRateLimitPerSecond = 1)
            assertEquals(AdmissionDecision.Allowed, fixture.limiter.acquire(10, 7, policy))
            assertEquals(AdmissionDecision.RoomRateLimited, fixture.limiter.acquire(10, 8, policy))
        }
    }

    @Test
    fun `large slow mode TTL remains positive and rejects the next attempt`() {
        Fixture().use { fixture ->
            val policy = AdmissionPolicy(slowModeSeconds = Int.MAX_VALUE)
            assertEquals(AdmissionDecision.Allowed, fixture.limiter.acquire(10, 7, policy))
            val ttl = requireNotNull(fixture.redis.getExpire("${fixture.prefix}{10}:slow:user:7", TimeUnit.MILLISECONDS))
            assertTrue(ttl > Int.MAX_VALUE.toLong())
            assertTrue(ttl <= Int.MAX_VALUE.toLong() * 1_000)
            assertEquals(AdmissionDecision.SlowModeActive, fixture.limiter.acquire(10, 7, policy))
        }
    }

    @Test
    fun `wrong Redis key type returns an unavailable decision with its cause`() {
        Fixture().use { fixture ->
            fixture.redis.opsForList().rightPush("${fixture.prefix}{10}:rate:room:${fixture.clock.instant().epochSecond}", "wrong-type")
            val decision = fixture.limiter.acquire(10, 7, AdmissionPolicy(roomRateLimitPerSecond = 1))
            assertTrue(decision is AdmissionDecision.Unavailable)
            assertNotNull((decision as AdmissionDecision.Unavailable).cause)
        }
    }

    private class Fixture : AutoCloseable {
        private val factory = LettuceConnectionFactory("127.0.0.1", System.getenv("CHAT_TEST_REDIS_PORT").toInt()).also {
            it.afterPropertiesSet()
            it.start()
        }
        val redis = StringRedisTemplate(factory)
        val prefix = "admission-test:${UUID.randomUUID()}:"
        val clock: Clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
        val limiter = RedisMessageRateLimiter(redis, ChatRedisProperties(admission = ChatRedisProperties.Admission(keyPrefix = prefix, rateLimitWindowTtl = Duration.ofMinutes(1))), clock)

        override fun close() {
            try {
                redis.delete(redis.keys("$prefix*"))
            } finally {
                factory.destroy()
            }
        }
    }
}
