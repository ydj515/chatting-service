package com.chat.persistence.service

import com.chat.core.service.SessionTokenRevocationStore
import com.chat.persistence.config.ChatAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier

class HmacSessionTokenServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC)
    private val properties = ChatAuthProperties(
        session = ChatAuthProperties.Session(
            secret = "test-secret-that-is-long-enough-for-hmac",
            ttl = Duration.ofMinutes(30),
        ),
    )

    @Test
    fun `Spring startup rejects missing signing key and accepts an explicit key`() {
        val runner = ApplicationContextRunner()
            .withUserConfiguration(SigningTestConfig::class.java)
            .withBean(Clock::class.java, Supplier { clock })
            .withBean(SessionTokenRevocationStore::class.java, Supplier { InMemoryRevocationStore() })
        runner.run { context -> assertTrue(context.startupFailure != null) }
        runner.withPropertyValues("chat.auth.session.secret=${properties.session.secret}").run { context ->
            assertNull(context.startupFailure)
            val service = context.getBean(HmacSessionTokenService::class.java)
            assertEquals(42L, service.authenticate(service.issueToken(42L).token)?.userId)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatAuthProperties::class)
    @Import(HmacSessionTokenService::class)
    class SigningTestConfig

    @Test
    fun `missing blank short and former default signing keys prevent service initialization`() {
        listOf("", " ".repeat(40), "too-short", "local-development-session-secret-change-me").forEach { secret ->
            val invalid = properties.copy(session = properties.session.copy(secret = secret))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                HmacSessionTokenService(invalid, clock, InMemoryRevocationStore())
            }
            assertEquals("Configure CHAT_AUTH_SESSION_SECRET with an independent signing key of at least 32 bytes", failure.message)
        }
    }

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

        override fun isTokenDigestRevoked(tokenDigest: String): Boolean = revokedTokens.any { SessionTokenDigests.sha256(it) == tokenDigest }

        override fun userRevokedAt(userId: Long): Instant? = revokedUsers[userId]
    }
}
