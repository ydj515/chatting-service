package com.chat.core.auth.service

import com.chat.core.dto.AuthenticatedSession
import com.chat.core.service.SessionTokenRevocationStore
import com.chat.core.service.SessionTokenService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class WebSocketTicketSessionPolicyTest {
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val sessions = mock(SessionTokenService::class.java)
    private val revocations = mock(SessionTokenRevocationStore::class.java)
    private val policy = WebSocketTicketSessionPolicy(sessions, revocations, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `ticket issuance requires authenticated token owner to match requested user`() {
        val session = AuthenticatedSession(7, LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC))
        `when`(sessions.authenticate("token")).thenReturn(session)
        assertSame(session, policy.authenticate(7, "token"))
        assertNull(policy.authenticate(8, "token"))
        assertNull(policy.authenticate(7, "unknown"))
    }

    @Test
    fun `unbound or expired parent cannot reach revocation storage`() {
        assertFalse(policy.isValid(7, null, now.epochSecond, now.epochSecond + 60))
        assertFalse(policy.isValid(7, "digest", now.epochSecond, null))
        assertFalse(policy.isValid(7, "digest", now.epochSecond - 60, now.epochSecond))
        verifyNoInteractions(revocations)
    }

    @Test
    fun `user cutoff rejects missing or same second issuance but permits newer sessions`() {
        `when`(revocations.userRevokedAt(7)).thenReturn(now)
        assertFalse(policy.isValid(7, "digest", null, now.epochSecond + 60))
        assertFalse(policy.isValid(7, "digest", now.epochSecond, now.epochSecond + 60))
        assertTrue(policy.isValid(7, "digest", now.epochSecond + 1, now.epochSecond + 60))
    }

    @Test
    fun `individual revocation overrides otherwise valid parent session`() {
        assertTrue(policy.isValid(7, "digest", null, now.epochSecond + 60))
        `when`(revocations.isTokenDigestRevoked("digest")).thenReturn(true)
        assertFalse(policy.isValid(7, "digest", now.epochSecond + 1, now.epochSecond + 60))
    }
}
