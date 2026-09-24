package com.chat.websocket.service

import com.chat.core.auth.service.WebSocketTicketSessionPolicy
import com.chat.core.service.SessionTokenRevocationStore
import com.chat.core.service.SessionTokenService
import com.chat.websocket.config.ChatWebSocketGatewayProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class WebSocketSessionAuthorizationTest {
    private val now = Instant.parse("2026-09-21T00:00:00Z")
    private val revocations = mock(SessionTokenRevocationStore::class.java)
    private val authorization = WebSocketSessionAuthorization(
        WebSocketTicketSessionPolicy(mock(SessionTokenService::class.java), revocations, Clock.fixed(now, ZoneOffset.UTC)),
    )

    @Test
    fun `expired parent sessions are rejected independently of ticket lifetime`() {
        assertTrue(authorization.isInvalid(session(expiresAt = now.epochSecond)))
        assertFalse(authorization.isInvalid(session(expiresAt = now.epochSecond + 60)))
    }

    @Test
    fun `individual logout rejects only the matching connected session`() {
        val first = session(digest = "first")
        val second = session(digest = "second")
        assertFalse(authorization.isInvalid(first))
        `when`(revocations.isTokenDigestRevoked("first")).thenReturn(true)
        assertTrue(authorization.isInvalid(first))
        assertFalse(authorization.isInvalid(second))
    }

    @Test
    fun `user cutoff and revocation lookup failures fail closed`() {
        `when`(revocations.userRevokedAt(7)).thenReturn(now)
        assertTrue(authorization.isInvalid(session()))
        `when`(revocations.isTokenDigestRevoked("digest")).thenThrow(IllegalStateException("offline"))
        assertTrue(authorization.isInvalid(session()))
        val missing = mock(WebSocketSession::class.java)
        `when`(missing.attributes).thenReturn(mutableMapOf())
        assertTrue(authorization.isInvalid(missing))
    }

    @Test
    fun `heartbeat disabled still closes expired sessions and queued delivery rechecks revocation`() {
        val transport = WebSocketSessionTransport(
            ChatWebSocketGatewayProperties(heartbeatEnabled = false), authorization, Executor { it.run() },
        )
        val expired = session(expiresAt = now.epochSecond)
        var removed = false
        val managed = transport.create(7, expired) { _, _ -> removed = true }
        transport.pollHeartbeats(listOf(managed), now.toEpochMilli()) { _, _ -> removed = true }
        assertTrue(removed)
        verify(expired).close(CloseStatus(4003, "Session expired or revoked"))

        val active = session()
        var task: Runnable? = null
        val queuedTransport = WebSocketSessionTransport(
            ChatWebSocketGatewayProperties(), authorization, Executor { task = it },
        )
        val queued = queuedTransport.create(7, active) { _, _ -> }
        queued.outboundQueue.enqueue("private message")
        `when`(revocations.isTokenDigestRevoked("digest")).thenReturn(true)
        checkNotNull(task).run()
        verify(active).close(CloseStatus(4003, "Session expired or revoked"))
        org.mockito.Mockito.verify(active, org.mockito.Mockito.never()).sendMessage(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `executor rejection closes the socket and removes managed state`() {
        val transport = WebSocketSessionTransport(
            ChatWebSocketGatewayProperties(), authorization,
            Executor { throw RejectedExecutionException("saturated") },
        )
        val socket = session()
        var removed = false
        val managed = transport.create(7, socket) { _, _ -> removed = true }
        assertFalse(managed.outboundQueue.enqueue("payload"))
        assertTrue(removed)
        assertTrue(managed.outboundQueue.isClosed())
        verify(socket).close(CloseStatus(1013, "Outbound executor unavailable"))
    }

    private fun session(digest: String = "digest", expiresAt: Long = now.epochSecond + 60): WebSocketSession =
        mock(WebSocketSession::class.java).also {
            `when`(it.id).thenReturn(digest)
            `when`(it.isOpen).thenReturn(true)
            `when`(it.attributes).thenReturn(
                mutableMapOf<String, Any>(
                    WebSocketSessionIdentity.ATTRIBUTE to WebSocketSessionIdentity(7, digest, now.epochSecond - 60, expiresAt),
                ),
            )
        }
}
