package com.chat.api.security

import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.service.SessionTokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime

class AuthenticatedUserResolverTest {

    @Test
    fun `Authorization bearer token이 있으면 token의 사용자 ID를 우선 사용한다`() {
        val sessionTokenService = mock(SessionTokenService::class.java)
        `when`(sessionTokenService.authenticate("valid-token")).thenReturn(
            AuthenticatedSession(
                userId = 42L,
                expiresAt = LocalDateTime.parse("2026-06-12T12:30:00"),
            )
        )
        val resolver = AuthenticatedUserResolver(sessionTokenService)

        val userId = resolver.resolveRequired("Bearer valid-token", explicitUserId = 999L)

        assertEquals(42L, userId)
        verify(sessionTokenService).authenticate("valid-token")
    }

    @Test
    fun `token이 없으면 호환성을 위해 명시적 사용자 ID를 사용한다`() {
        val resolver = AuthenticatedUserResolver(mock(SessionTokenService::class.java))

        assertEquals(999L, resolver.resolveRequired(null, explicitUserId = 999L))
    }

    @Test
    fun `Authorization token이 유효하지 않으면 명시적 사용자 ID로 fallback하지 않는다`() {
        val sessionTokenService = mock(SessionTokenService::class.java)
        `when`(sessionTokenService.authenticate("bad-token")).thenReturn(null)
        val resolver = AuthenticatedUserResolver(sessionTokenService)

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveRequired("Bearer bad-token", explicitUserId = 999L)
        }
    }

    @Test
    fun `token과 명시적 사용자 ID가 모두 없으면 예외를 던진다`() {
        val resolver = AuthenticatedUserResolver(mock(SessionTokenService::class.java))

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveRequired(null, explicitUserId = null)
        }
    }
}
