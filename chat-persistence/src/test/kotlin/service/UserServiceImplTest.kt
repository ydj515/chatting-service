package com.chat.persistence.service

import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.dto.SessionToken
import com.chat.domain.model.User
import com.chat.domain.service.SessionTokenService
import com.chat.persistence.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.security.MessageDigest
import java.time.LocalDateTime

class UserServiceImplTest {

    @Test
    fun `이미 존재하는 사용자명은 상태 충돌 예외로 처리한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        `when`(userRepository.existsByUsername("tester")).thenReturn(true)
        val userService = UserServiceImpl(userRepository, sessionTokenService)

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
        `when`(sessionTokenService.issueToken(7L)).thenReturn(SessionToken("session-token-7", expiresAt))
        val userService = UserServiceImpl(userRepository, sessionTokenService)

        val response = userService.login(LoginRequest(username = "tester", password = "password"))

        assertEquals(7L, response.user.id)
        assertEquals("session-token-7", response.sessionToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(expiresAt, response.expiresAt)
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
