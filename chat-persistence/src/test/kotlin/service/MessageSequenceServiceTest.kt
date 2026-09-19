package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID

class MessageSequenceServiceTest {
    @Test
    fun `allocation failure is never returned as a valid sequence`() {
        @Suppress("UNCHECKED_CAST")
        val redis = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val service = MessageSequenceService(redis, ChatRedisProperties())
        assertThrows(IllegalStateException::class.java) { service.getNextSequence(1) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CHAT_TEST_REDIS_PORT", matches = ".+")
    fun `allocation removes legacy TTL and stays monotonic across gateways`() {
        val factory = LettuceConnectionFactory("127.0.0.1", System.getenv("CHAT_TEST_REDIS_PORT").toInt())
        factory.afterPropertiesSet()
        factory.start()
        try {
            val redis = StringRedisTemplate(factory)
            val prefix = "review-sequence:${UUID.randomUUID()}"
            val properties = ChatRedisProperties(sequenceKeyPrefix = prefix)
            val first = MessageSequenceService(redis, properties)
            val second = MessageSequenceService(redis, properties)
            val key = "$prefix:1"
            // Existing expiring counters must retain the precise 64-bit value.
            redis.opsForValue().set(key, "9007199254740992", Duration.ofSeconds(30))
            assertEquals(9007199254740993L, first.getNextSequence(1))
            assertEquals(-1L, redis.getExpire(key))
            assertEquals(9007199254740994L, second.getNextSequence(1))
            assertEquals(1L, first.getNextSequence(2))
            assertEquals(-1L, redis.getExpire("$prefix:2"))
            redis.delete(listOf(key, "$prefix:2"))
        } finally {
            factory.destroy()
        }
    }
}
