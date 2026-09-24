package com.chat.core.user.service

import com.chat.core.dto.SessionToken
import com.chat.core.dto.UserSanctionType
import com.chat.core.service.SessionTokenService
import com.chat.core.user.command.CreateUserCommand
import com.chat.core.user.command.LoginCommand
import com.chat.core.user.port.LoginSanction
import com.chat.core.user.port.LoginSanctionReader
import com.chat.core.user.port.PasswordHashing
import com.chat.core.user.port.PasswordVerification
import com.chat.core.user.port.UserStore
import com.chat.domain.exception.ResourceNotFoundException
import com.chat.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserServiceImplTest {
    private val users = mock(UserStore::class.java)
    private val sessions = mock(SessionTokenService::class.java)
    private val sanctions = mock(LoginSanctionReader::class.java)
    private val passwords = mock(PasswordHashing::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
    private val service = UserServiceImpl(users, sessions, sanctions, clock, passwords)
    private val user = User(id = 7, username = "tester", password = "stored", displayName = "Tester")

    @Test
    fun `invalid registration cannot reach persistence or password hashing`() {
        val valid = CreateUserCommand("tester", "password", "Tester")
        listOf(
            valid.copy(username = " "), valid.copy(username = "a".repeat(21)),
            valid.copy(displayName = " "), valid.copy(displayName = "a".repeat(51)),
            valid.copy(password = "ab"), valid.copy(password = "   "), valid.copy(password = "가".repeat(25)),
        ).forEach { input -> assertThrows(IllegalArgumentException::class.java) { service.createUser(input) } }
        verifyNoInteractions(users, passwords, sessions, sanctions)
    }

    @Test
    fun `invalid login cannot reach password verification or token issuance`() {
        listOf(LoginCommand(" ", "password"), LoginCommand("tester", " "), LoginCommand("tester", "가".repeat(25))).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { service.login(input) }
        }
        verifyNoInteractions(users, passwords, sessions, sanctions)
    }

    @Test
    fun `failed password verification cannot inspect sanctions or issue sessions`() {
        `when`(users.findByUsername("tester")).thenReturn(user)
        `when`(passwords.verify("wrong", "stored")).thenReturn(PasswordVerification(false, false))
        assertThrows(IllegalArgumentException::class.java) { service.login(LoginCommand("tester", "wrong")) }
        verifyNoInteractions(sanctions, sessions)
        verify(users, never()).updatePassword(anyLong(), anyString())
    }

    @Test
    fun `expired suspension permits login and rehash precedes token issuance`() {
        `when`(users.findByUsername("tester")).thenReturn(user)
        `when`(passwords.verify("password", "stored")).thenReturn(PasswordVerification(true, true))
        `when`(passwords.encode("password")).thenReturn("new-hash")
        `when`(sanctions.activeGlobalSanctionsForUser(7)).thenReturn(listOf(LoginSanction(UserSanctionType.SUSPEND, clock.instant())))
        `when`(sessions.issueToken(7)).thenReturn(SessionToken("token", LocalDateTime.now(clock).plusHours(1)))

        val response = service.login(LoginCommand("tester", "password"))

        assertEquals(7L, response.user.id)
        assertEquals("token", response.sessionToken)
        inOrder(users, sessions).apply {
            verify(users).updatePassword(7, "new-hash")
            verify(sessions).issueToken(7)
        }
    }

    @Test
    fun `an indefinite suspension blocks password upgrade and token issuance`() {
        `when`(users.findByUsername("tester")).thenReturn(user)
        `when`(passwords.verify("password", "stored")).thenReturn(PasswordVerification(true, true))
        `when`(sanctions.activeGlobalSanctionsForUser(7)).thenReturn(listOf(LoginSanction(UserSanctionType.SUSPEND, null)))

        assertThrows(IllegalStateException::class.java) { service.login(LoginCommand("tester", "password")) }
        verifyNoInteractions(sessions)
        verify(passwords, never()).encode(anyString())
        verify(users, never()).updatePassword(anyLong(), anyString())
    }

    @Test
    fun `missing users produce not found without a write`() {
        assertThrows(ResourceNotFoundException::class.java) { service.getUserById(99) }
        assertThrows(ResourceNotFoundException::class.java) { service.updateLastSeen(99) }
        verify(users, times(2)).findById(99)
        verifyNoMoreInteractions(users)
    }

    @Test
    fun `search preserves paging metadata and exposes no password`() {
        val page = PageRequest.of(1, 2)
        `when`(users.searchUsers("test", page)).thenReturn(PageImpl(listOf(user), page, 3))
        val result = service.searchUsers("test", page)
        assertEquals(3L, result.totalElements)
        assertEquals(1, result.number)
        assertEquals("Tester", result.content.single().displayName)
    }
}
