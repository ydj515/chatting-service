package com.chat.api.security

import com.chat.domain.service.SessionTokenService
import org.springframework.stereotype.Component

@Component
class AuthenticatedUserResolver(
    private val sessionTokenService: SessionTokenService,
) {
    fun resolveRequired(authorizationHeader: String?, explicitUserId: Long? = null): Long {
        val token = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (token != null) {
            return sessionTokenService.authenticate(token)?.userId
                ?: throw IllegalArgumentException("유효하지 않은 인증 토큰입니다.")
        }

        return explicitUserId
            ?: throw IllegalArgumentException("인증 사용자 정보가 필요합니다.")
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
