package com.chat.persistence.service

import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.persistence.config.ChatAuthProperties
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Service
class RedisSessionTokenRevocationStore(
    private val redisTemplate: RedisTemplate<String, String>,
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
) : SessionTokenRevocationStore {

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun revokeToken(token: String, expiresAt: Instant) {
        val ttl = Duration.between(clock.instant(), expiresAt)
        if (!ttl.isPositive) {
            return
        }

        redisTemplate.opsForValue().set(tokenKey(token), "1", ttl)
    }

    override fun revokeUserTokens(userId: Long, revokedAt: Instant) {
        redisTemplate.opsForValue().set(
            userKey(userId),
            revokedAt.epochSecond.toString(),
            authProperties.session.ttl.plus(authProperties.session.userRevocationGraceTtl),
        )
    }

    override fun isTokenRevoked(token: String): Boolean {
        return redisTemplate.opsForValue().get(tokenKey(token)) != null
    }

    override fun userRevokedAt(userId: Long): Instant? {
        return redisTemplate.opsForValue().get(userKey(userId))
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it) }
    }

    private fun tokenKey(token: String): String {
        return "${authProperties.session.revocationKeyPrefix}token:${hash(token)}"
    }

    private fun userKey(userId: Long): String {
        return "${authProperties.session.revocationKeyPrefix}user:$userId"
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return encoder.encodeToString(digest)
    }
}
