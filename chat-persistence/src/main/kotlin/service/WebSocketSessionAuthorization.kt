package com.chat.persistence.service

import com.chat.core.dto.AuthenticatedSession
import org.springframework.stereotype.Service
import org.springframework.web.socket.WebSocketSession
import java.time.ZoneOffset

/** Parent session claims retained without keeping the bearer token on the connection. */
data class WebSocketSessionIdentity(
    val userId: Long,
    val tokenDigest: String,
    val issuedAt: Long?,
    val expiresAt: Long,
) {
    companion object {
        const val ATTRIBUTE = "chat.auth.session.identity"

        fun fromToken(session: AuthenticatedSession, token: String): WebSocketSessionIdentity = from(session, SessionTokenDigests.sha256(token))

        fun from(session: AuthenticatedSession, digest: String): WebSocketSessionIdentity = WebSocketSessionIdentity(
            userId = session.userId,
            tokenDigest = digest,
            issuedAt = session.issuedAt?.toEpochSecond(ZoneOffset.UTC),
            expiresAt = session.expiresAt.toEpochSecond(ZoneOffset.UTC),
        )
    }
}

@Service
class WebSocketSessionAuthorization(private val policy: WebSocketTicketSessionPolicy) {
    fun isInvalid(session: WebSocketSession): Boolean {
        val identity = session.attributes[WebSocketSessionIdentity.ATTRIBUTE] as? WebSocketSessionIdentity ?: return true
        return try {
            !policy.isValid(identity.userId, identity.tokenDigest, identity.issuedAt, identity.expiresAt)
        } catch (_: Exception) {
            // A revocation-store outage must not leave an authenticated connection usable indefinitely.
            true
        }
    }
}
