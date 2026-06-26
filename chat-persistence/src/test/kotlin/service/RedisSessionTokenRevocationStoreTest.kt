package com.chat.persistence.service

import com.chat.persistence.config.ChatAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RedisSessionTokenRevocationStoreTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `token revoke는 원문 token 대신 hash key를 저장하고 만료까지 ttl을 건다`() {
        val redisTemplate = mockRedisTemplate()
        val store = store(redisTemplate.template)

        store.revokeToken("plain-session-token", clock.instant().plusSeconds(60))

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(redisTemplate.valueOps).set(keyCaptor.capture(), eq("1"), eq(Duration.ofSeconds(60)))
        assertFalse(keyCaptor.value.contains("plain-session-token"))
        assertTrue(keyCaptor.value.startsWith("chat:auth:session:revoked:token:"))
    }

    @Test
    fun `user revoke marker는 epoch second를 저장하고 다시 읽는다`() {
        val redisTemplate = mockRedisTemplate()
        val store = store(redisTemplate.template)

        store.revokeUserTokens(7L, clock.instant())
        verify(redisTemplate.valueOps).set(
            eq("chat:auth:session:revoked:user:7"),
            eq("1782518400"),
            eq(Duration.ofHours(13)),
        )

        `when`(redisTemplate.valueOps.get("chat:auth:session:revoked:user:7")).thenReturn("1782518400")
        assertEquals(clock.instant(), store.userRevokedAt(7L))
    }

    private fun store(redisTemplate: RedisTemplate<String, String>) = RedisSessionTokenRevocationStore(
        redisTemplate = redisTemplate,
        authProperties = ChatAuthProperties(
            session = ChatAuthProperties.Session(
                secret = "test-secret",
                ttl = Duration.ofHours(12),
                userRevocationGraceTtl = Duration.ofHours(1),
            )
        ),
        clock = clock,
    )

    private data class MockRedis(
        val template: RedisTemplate<String, String>,
        val valueOps: ValueOperations<String, String>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mockRedisTemplate(): MockRedis {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOps)
        return MockRedis(template, valueOps)
    }
}
