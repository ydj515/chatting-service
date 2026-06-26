package com.chat.persistence.service

import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.dto.SessionToken
import com.chat.domain.service.SessionTokenService
import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.persistence.config.ChatAuthProperties
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class HmacSessionTokenService(
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
    private val revocationStore: SessionTokenRevocationStore,
) : SessionTokenService {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun issueToken(userId: Long): SessionToken {
        val issuedAtInstant = clock.instant()
        val expiresAtInstant = issuedAtInstant.plus(authProperties.session.ttl)
        val payload = listOf(
            userId.toString(),
            issuedAtInstant.epochSecond.toString(),
            expiresAtInstant.epochSecond.toString(),
            UUID.randomUUID().toString().replace("-", ""),
        ).joinToString(":")
        val encodedPayload = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = sign(encodedPayload)

        return SessionToken(
            token = "v1.$encodedPayload.$signature",
            expiresAt = LocalDateTime.ofInstant(expiresAtInstant, ZoneOffset.UTC),
        )
    }

    override fun authenticate(token: String): AuthenticatedSession? {
        val claims = parseAndVerify(token) ?: return null
        if (isRevoked(token, claims)) {
            return null
        }

        return AuthenticatedSession(
            userId = claims.userId,
            expiresAt = LocalDateTime.ofInstant(claims.expiresAt, ZoneOffset.UTC),
        )
    }

    override fun revokeToken(token: String): Boolean {
        val claims = parseAndVerify(token) ?: return false
        if (!claims.expiresAt.isAfter(clock.instant())) {
            return false
        }

        revocationStore.revokeToken(token, claims.expiresAt)
        return true
    }

    override fun revokeUserTokens(userId: Long) {
        revocationStore.revokeUserTokens(userId, clock.instant())
    }

    private fun parseAndVerify(token: String): TokenClaims? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != "v1") {
            return null
        }

        val encodedPayload = parts[1]
        val providedSignature = parts[2]
        val expectedSignature = sign(encodedPayload)
        if (!MessageDigest.isEqual(
                providedSignature.toByteArray(StandardCharsets.UTF_8),
                expectedSignature.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            return null
        }

        val payload = runCatching {
            String(decoder.decode(encodedPayload), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val payloadParts = payload.split(':')
        if (payloadParts.size != 3) {
            if (payloadParts.size != 4) {
                return null
            }
        }

        val userId = payloadParts[0].toLongOrNull() ?: return null
        val issuedAtEpochSecond: Long?
        val expiresAtEpochSecond: Long
        if (payloadParts.size == 3) {
            issuedAtEpochSecond = null
            expiresAtEpochSecond = payloadParts[1].toLongOrNull() ?: return null
        } else {
            issuedAtEpochSecond = payloadParts[1].toLongOrNull() ?: return null
            expiresAtEpochSecond = payloadParts[2].toLongOrNull() ?: return null
        }
        if (clock.instant().epochSecond >= expiresAtEpochSecond) {
            return null
        }

        return TokenClaims(
            userId = userId,
            issuedAt = issuedAtEpochSecond?.let { Instant.ofEpochSecond(it) },
            expiresAt = Instant.ofEpochSecond(expiresAtEpochSecond),
        )
    }

    private fun isRevoked(token: String, claims: TokenClaims): Boolean {
        return try {
            if (revocationStore.isTokenRevoked(token)) {
                return true
            }

            val revokedAt = revocationStore.userRevokedAt(claims.userId) ?: return false
            claims.issuedAt == null || !claims.issuedAt.isAfter(revokedAt)
        } catch (_: Exception) {
            true
        }
    }

    private fun sign(encodedPayload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(
            authProperties.session.secret.toByteArray(StandardCharsets.UTF_8),
            "HmacSHA256",
        )
        mac.init(key)
        return encoder.encodeToString(mac.doFinal(encodedPayload.toByteArray(StandardCharsets.UTF_8)))
    }

    private data class TokenClaims(
        val userId: Long,
        val issuedAt: Instant?,
        val expiresAt: Instant,
    )
}
