package com.chat.core.dto

import java.time.LocalDateTime

data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val profileImageUrl: String?,
    val status: String?,
    val isActive: Boolean,
    val lastSeenAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

data class LoginResponse(
    val user: UserDto,
    val sessionToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: LocalDateTime,
)

data class SessionToken(
    val token: String,
    val expiresAt: LocalDateTime,
)

data class AuthenticatedSession(
    val userId: Long,
    val expiresAt: LocalDateTime,
    val issuedAt: LocalDateTime? = null,
)

data class WebSocketTicketResponse(
    val ticket: String,
    val expiresAt: LocalDateTime,
)

data class AuthenticatedWebSocketTicket(
    val userId: Long,
    val expiresAt: LocalDateTime,
    val sessionTokenDigest: String? = null,
    val parentSession: AuthenticatedSession? = null,
)
