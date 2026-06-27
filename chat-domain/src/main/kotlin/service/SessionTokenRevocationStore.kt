package com.chat.domain.service

import java.time.Instant

interface SessionTokenRevocationStore {
    fun revokeToken(token: String, expiresAt: Instant)

    fun revokeUserTokens(userId: Long, revokedAt: Instant)

    fun isTokenRevoked(token: String): Boolean

    fun userRevokedAt(userId: Long): Instant?
}
