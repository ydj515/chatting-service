package com.chat.persistence.service

import com.chat.persistence.config.ChatAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        val service = HmacSessionTokenService(properties, clock)

        val issued = service.issueToken(42L)
        val authenticated = service.authenticate(issued.token)

        assertEquals(42L, authenticated?.userId)
        assertEquals(issued.expiresAt, authenticated?.expiresAt)
    }

    @Test
    fun `서명이 변조된 세션 토큰은 검증하지 않는다`() {
        val service = HmacSessionTokenService(properties, clock)
        val issued = service.issueToken(42L)

        val tampered = issued.token.replaceAfterLast('.', "tampered-signature")

        assertNull(service.authenticate(tampered))
    }

    @Test
    fun `만료된 세션 토큰은 검증하지 않는다`() {
        val issued = HmacSessionTokenService(properties, clock).issueToken(42L)
        val laterClock = Clock.fixed(clock.instant().plus(Duration.ofMinutes(31)), ZoneOffset.UTC)

        val authenticated = HmacSessionTokenService(properties, laterClock).authenticate(issued.token)

        assertNull(authenticated)
    }
}
