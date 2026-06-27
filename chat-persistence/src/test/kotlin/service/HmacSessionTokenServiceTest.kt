package com.chat.persistence.service

import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.persistence.config.ChatAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class HmacSessionTokenServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC)
    private val properties = ChatAuthProperties(
        session = ChatAuthProperties.Session(
            secret = "test-secret-that-is-long-enough-for-hmac",
            ttl = Duration.ofMinutes(30),
        )
    )

    @Test
    fun `발급한 세션 토큰은 같은 서비스에서 사용자 ID로 검증된다`() {
        val service = HmacSessionTokenService(properties, clock, InMemoryRevocationStore())

        val issued = service.issueToken(42L)
        val authenticated = service.authenticate(issued.token)

        assertEquals(42L, authenticated?.userId)
        assertEquals(issued.expiresAt, authenticated?.expiresAt)
    }

    @Test
    fun `서명이 변조된 세션 토큰은 검증하지 않는다`() {
        val service = HmacSessionTokenService(properties, clock, InMemoryRevocationStore())
        val issued = service.issueToken(42L)

        val tampered = issued.token.replaceAfterLast('.', "tampered-signature")

        assertNull(service.authenticate(tampered))
    }

    @Test
    fun `만료된 세션 토큰은 검증하지 않는다`() {
        val issued = HmacSessionTokenService(properties, clock, InMemoryRevocationStore()).issueToken(42L)
        val laterClock = Clock.fixed(clock.instant().plus(Duration.ofMinutes(31)), ZoneOffset.UTC)

        val authenticated = HmacSessionTokenService(properties, laterClock, InMemoryRevocationStore())
            .authenticate(issued.token)

        assertNull(authenticated)
    }

    @Test
    fun `revoke된 session token은 인증하지 않는다`() {
        val revocationStore = InMemoryRevocationStore()
        val service = HmacSessionTokenService(properties, clock, revocationStore)
        val issued = service.issueToken(42L)

        assertTrue(service.revokeToken(issued.token))

        assertNull(service.authenticate(issued.token))
    }

    @Test
    fun `user revoke 이후 기존 token은 인증하지 않고 이후 발급 token은 인증한다`() {
        val revocationStore = InMemoryRevocationStore()
        val service = HmacSessionTokenService(properties, clock, revocationStore)
        val oldToken = service.issueToken(42L)

        service.revokeUserTokens(42L)

        assertNull(service.authenticate(oldToken.token))

        val laterClock = Clock.fixed(clock.instant().plusSeconds(1), ZoneOffset.UTC)
        val laterService = HmacSessionTokenService(properties, laterClock, revocationStore)
        val newToken = laterService.issueToken(42L)

        assertEquals(42L, laterService.authenticate(newToken.token)?.userId)
    }

    private class InMemoryRevocationStore : SessionTokenRevocationStore {
        private val revokedTokens = mutableSetOf<String>()
        private val revokedUsers = mutableMapOf<Long, Instant>()

        override fun revokeToken(token: String, expiresAt: Instant) {
            revokedTokens += token
        }

        override fun revokeUserTokens(userId: Long, revokedAt: Instant) {
            revokedUsers[userId] = revokedAt
        }

        override fun isTokenRevoked(token: String): Boolean = token in revokedTokens

        override fun userRevokedAt(userId: Long): Instant? = revokedUsers[userId]
    }
}
