package com.chat.persistence.service

import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.persistence.config.ChatAuthProperties
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class RedisSessionTokenRevocationStore(
    private val redisTemplate: RedisTemplate<String, String>,
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
) : SessionTokenRevocationStore {
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

    override fun isTokenRevoked(token: String): Boolean = isTokenDigestRevoked(SessionTokenDigests.sha256(token))

    override fun isTokenDigestRevoked(tokenDigest: String): Boolean =
        redisTemplate.opsForValue().get("${authProperties.session.revocationKeyPrefix}token:$tokenDigest") != null

    override fun userRevokedAt(userId: Long): Instant? =
        redisTemplate.opsForValue().get(userKey(userId))
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it) }

    private fun tokenKey(token: String): String = "${authProperties.session.revocationKeyPrefix}token:${SessionTokenDigests.sha256(token)}"

    private fun userKey(userId: Long): String = "${authProperties.session.revocationKeyPrefix}user:$userId"
}
