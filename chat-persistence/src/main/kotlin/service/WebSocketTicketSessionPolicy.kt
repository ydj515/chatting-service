package com.chat.persistence.service

import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.domain.service.SessionTokenService
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class WebSocketTicketSessionPolicy(
    private val sessionTokenService: SessionTokenService,
    private val revocationStore: SessionTokenRevocationStore,
    private val clock: Clock,
) {
    fun authenticate(userId: Long, token: String): AuthenticatedSession? =
        sessionTokenService.authenticate(token)?.takeIf { it.userId == userId }

    fun isValid(userId: Long, digest: String?, issuedAt: Long?, expiresAt: Long?): Boolean {
        // Legacy unbound tickets must be reissued; absence of revocation metadata is not authorization.
        if (digest == null || expiresAt == null) return false
        if (clock.instant().epochSecond >= expiresAt || revocationStore.isTokenDigestRevoked(digest)) return false
        val revokedAt = revocationStore.userRevokedAt(userId) ?: return true
        return issuedAt != null && issuedAt > revokedAt.epochSecond
    }
}
