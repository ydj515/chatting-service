package com.chat.persistence.service

import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.SessionToken
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.model.User
import com.chat.domain.service.SessionTokenService
import com.chat.persistence.repository.UserRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserServiceImplTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneOffset.UTC)
    private val passwordEncoder: PasswordEncoder = BCryptPasswordEncoder(4)

    @Test
    fun `last seen uses the same injected clock as authentication`() {
        val repository = mock(UserRepository::class.java)
        val user = User(id = 7, username = "tester", password = "unused", displayName = "Tester")
        `when`(repository.findById(7)).thenReturn(java.util.Optional.of(user))
        val service = userService(repository, mock(SessionTokenService::class.java), mock(UserSanctionJdbcRepository::class.java))
        val result = service.updateLastSeen(7)
        val expected = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        assertEquals(expected, result.lastSeenAt)
        verify(repository).updateLastSeenAt(7, expected)
    }

    @Test
    fun `이미 존재하는 사용자명은 상태 충돌 예외로 처리한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        `when`(userRepository.existsByUsername("tester")).thenReturn(true)
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        val exception = assertThrows(ResourceConflictException::class.java) {
            userService.createUser(
                CreateUserRequest(
                    username = "tester",
                    password = "abc",
                    displayName = "테스터",
                ),
            )
        }

        assertEquals("이미 존재하는 사용자명입니다: tester", exception.message)
    }

    @Test
    fun `사용자 생성은 plain SHA-256이 아닌 salted BCrypt hash를 저장한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        `when`(userRepository.existsByUsername("tester")).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { invocation -> invocation.arguments[0] as User }
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        userService.createUser(
            CreateUserRequest(
                username = "tester",
                password = "password",
                displayName = "테스터",
            ),
        )

        val captor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(captor.capture())
        val savedPassword = captor.value.password
        assertNotEquals("password", savedPassword)
        assertNotEquals(
            "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
            savedPassword,
        )
        assertTrue(savedPassword.startsWith("\$2"))
        assertTrue(passwordEncoder.matches("password", savedPassword))
    }

    @Test
    fun `같은 비밀번호로 만든 계정도 서로 다른 BCrypt hash를 저장한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        `when`(userRepository.existsByUsername("tester1")).thenReturn(false)
        `when`(userRepository.existsByUsername("tester2")).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { invocation -> invocation.arguments[0] as User }
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        userService.createUser(CreateUserRequest("tester1", "same-password", "테스터1"))
        userService.createUser(CreateUserRequest("tester2", "same-password", "테스터2"))

        val captor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository, times(2)).save(captor.capture())
        val savedPasswords = captor.allValues.map { it.password }
        assertNotEquals(savedPasswords[0], savedPasswords[1])
        assertTrue(savedPasswords.all { passwordEncoder.matches("same-password", it) })
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
            password = passwordEncoder.encode("password"),
            displayName = "테스터",
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            updatedAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(userRepository.findByUsername("tester")).thenReturn(user)
        `when`(userSanctionRepository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(sessionTokenService.issueToken(7L)).thenReturn(SessionToken("session-token-7", expiresAt))
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        val response = userService.login(LoginRequest(username = "tester", password = "password"))

        assertEquals(7L, response.user.id)
        assertEquals("session-token-7", response.sessionToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(expiresAt, response.expiresAt)
    }

    @Test
    fun `로그인은 기존 SHA-256 hash 사용자를 인증한 뒤 BCrypt hash로 마이그레이션한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val expiresAt = LocalDateTime.parse("2026-06-12T12:30:00")
        val user = User(
            id = 7L,
            username = "tester",
            password = legacySha256("password"),
            displayName = "테스터",
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            updatedAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(userRepository.findByUsername("tester")).thenReturn(user)
        `when`(userSanctionRepository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(sessionTokenService.issueToken(7L)).thenReturn(SessionToken("session-token-7", expiresAt))
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        val response = userService.login(LoginRequest(username = "tester", password = "password"))

        val passwordCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(userRepository).updatePassword(eq(7L), passwordCaptor.capture().orEmpty())
        assertTrue(passwordEncoder.matches("password", passwordCaptor.value))
        assertEquals("session-token-7", response.sessionToken)
    }

    @Test
    fun `BCrypt hash 로그인은 72 bytes 초과 입력을 비밀번호 불일치로 거부한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val user = User(
            id = 7L,
            username = "tester",
            password = passwordEncoder.encode("a".repeat(72)),
            displayName = "테스터",
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            updatedAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(userRepository.findByUsername("tester")).thenReturn(user)
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        assertThrows(IllegalArgumentException::class.java) {
            userService.login(LoginRequest(username = "tester", password = "a".repeat(73)))
        }

        verify(userSanctionRepository, never()).activeGlobalSanctionsForUser(7L)
        verifyNoInteractions(sessionTokenService)
    }

    @Test
    fun `logout은 session token revoke를 요청한다`() {
        val userRepository = mock(UserRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val userSanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

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
            password = passwordEncoder.encode("password"),
            displayName = "테스터",
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            updatedAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(userRepository.findByUsername("tester")).thenReturn(user)
        `when`(userSanctionRepository.activeGlobalSanctionsForUser(7L)).thenReturn(
            listOf(globalSuspend()),
        )
        val userService = userService(userRepository, sessionTokenService, userSanctionRepository)

        val exception = assertThrows(IllegalStateException::class.java) {
            userService.login(LoginRequest(username = "tester", password = "password"))
        }

        assertEquals("정지된 사용자는 로그인할 수 없습니다.", exception.message)
        verifyNoInteractions(sessionTokenService)
    }

    private fun userService(
        userRepository: UserRepository,
        sessionTokenService: SessionTokenService,
        userSanctionRepository: UserSanctionJdbcRepository,
    ): UserServiceImpl =
        UserServiceImpl(
            userRepository = userRepository,
            sessionTokenService = sessionTokenService,
            userSanctionRepository = userSanctionRepository,
            clock = clock,
            passwordEncoder = passwordEncoder,
        )

    private fun legacySha256(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun globalSuspend(): UserSanctionRecord =
        UserSanctionRecord(
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
