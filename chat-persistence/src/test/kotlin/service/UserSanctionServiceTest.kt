package com.chat.persistence.service

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UserSanctionServiceTest {

    @Test
    fun `mute 제재가 있으면 메시지 전송을 거부한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeSanctionsForUser(10L, 7L, now)).thenReturn(
            listOf(sanction(UserSanctionType.MUTE)),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        val exception = assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }

        assertEquals("user is restricted from sending messages", exception.message)
    }

    @Test
    fun `ban 제재가 있으면 메시지 전송을 거부한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeSanctionsForUser(10L, 7L, now)).thenReturn(
            listOf(sanction(UserSanctionType.BAN)),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }
    }

    @Test
    fun `활성 제재가 없으면 통과한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeSanctionsForUser(10L, 7L, now)).thenReturn(emptyList())
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        service.requireAllowedToSend(roomId = 10L, userId = 7L)
    }

    @Test
    fun `reserved suspend는 phase 8_5 메시지 전송 차단 대상에서 제외한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeSanctionsForUser(10L, 7L, now)).thenReturn(
            listOf(sanction(UserSanctionType.SUSPEND_RESERVED)),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        service.requireAllowedToSend(roomId = 10L, userId = 7L)
    }

    private fun sanction(type: UserSanctionType): UserSanctionRecord {
        return UserSanctionRecord(
            id = 1L,
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = type,
            reason = "spam",
            expiresAt = null,
            active = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            revokedBy = null,
            revokedAt = null,
        )
    }
}
