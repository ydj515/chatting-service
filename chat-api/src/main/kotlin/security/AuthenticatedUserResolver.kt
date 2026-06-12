package com.chat.api.security

import com.chat.domain.service.SessionTokenService
import org.springframework.stereotype.Component

@Component
class AuthenticatedUserResolver(
    private val sessionTokenService: SessionTokenService,
) {
    fun resolveRequired(authorizationHeader: String?): Long {
        val token = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("인증 토큰이 필요합니다.")

        return sessionTokenService.authenticate(token)?.userId
            ?: throw IllegalArgumentException("유효하지 않은 인증 토큰입니다.")
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
