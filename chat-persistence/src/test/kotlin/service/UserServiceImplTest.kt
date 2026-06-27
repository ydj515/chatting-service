package com.chat.persistence.service

import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.SessionToken
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.model.User
import com.chat.domain.service.SessionTokenService
import com.chat.persistence.repository.UserRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserServiceImplTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `이미 존재하는 사용자명은 상태 충돌 예외로 처리한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        `when`(userRepository.existsByUsername("tester")).thenReturn(true)
        val userService = UserServiceImpl(userRepository, sessionTokenService, userSanctionRepository, clock)

        val exception = assertThrows(IllegalStateException::class.java) {
            userService.createUser(
                CreateUserRequest(
                    username = "tester",
                    password = "abc",
                    displayName = "테스터",
                )
            )
        }

        assertEquals("이미 존재하는 사용자명입니다: tester", exception.message)
    }

    @Test
    fun `로그인은 사용자 정보와 세션 토큰을 함께 반환한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val expiresAt = LocalDateTime.parse("2026-06-12T12:30:00")
        val user = User(
            id = 7L,
            username = "tester",
            password = hashPassword("password"),
            displayName = "테스터",
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            updatedAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(userRepository.findByUsername("tester")).thenReturn(user)
        `when`(userSanctionRepository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(sessionTokenService.issueToken(7L)).thenReturn(SessionToken("session-token-7", expiresAt))
        val userService = UserServiceImpl(userRepository, sessionTokenService, userSanctionRepository, clock)

        val response = userService.login(LoginRequest(username = "tester", password = "password"))

        assertEquals(7L, response.user.id)
        assertEquals("session-token-7", response.sessionToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(expiresAt, response.expiresAt)
    }

    @Test
    fun `logout은 session token revoke를 요청한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val userService = UserServiceImpl(userRepository, sessionTokenService, userSanctionRepository, clock)

        userService.logout("session-token")

        verify(sessionTokenService).revokeToken("session-token")
    }

    @Test
    fun `global suspend가 활성인 사용자는 로그인할 수 없다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val user = User(
            id = 7L,
            username = "tester",
            password = hashPassword("password"),
            displayName = "테스터",
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            updatedAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(userRepository.findByUsername("tester")).thenReturn(user)
        `when`(userSanctionRepository.activeGlobalSanctionsForUser(7L)).thenReturn(
            listOf(globalSuspend()),
        )
        val userService = UserServiceImpl(userRepository, sessionTokenService, userSanctionRepository, clock)

        val exception = assertThrows(IllegalStateException::class.java) {
            userService.login(LoginRequest(username = "tester", password = "password"))
        }

        assertEquals("정지된 사용자는 로그인할 수 없습니다.", exception.message)
        verifyNoInteractions(sessionTokenService)
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun globalSuspend(): UserSanctionRecord {
        return UserSanctionRecord(
            id = 1L,
            scopeType = ModerationScopeType.GLOBAL,
            roomId = null,
            userId = 7L,
            type = UserSanctionType.SUSPEND,
            reason = "abuse",
            expiresAt = null,
            active = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-27T00:00:00Z"),
            revokedBy = null,
            revokedAt = null,
        )
    }
}
