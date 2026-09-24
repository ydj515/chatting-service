package com.chat.core.message.service

import com.chat.core.dto.ModerationScopeType
import com.chat.core.dto.UserSanctionType
import com.chat.core.message.port.ActiveMessageSanctions
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.MessageSanction
import com.chat.core.message.port.ModerationRejectionReason
import com.chat.domain.exception.MessageModerationRejectedException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UserSanctionServiceTest {
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val sanctions = mock(ActiveMessageSanctions::class.java)
    private val metrics = mock(MessagePolicyMetrics::class.java)
    private val service = UserSanctionService(sanctions, Clock.fixed(now, ZoneOffset.UTC), metrics)

    @Test
    fun `exact expiry is inactive but a later room sanction still blocks`() {
        `when`(sanctions.activeGlobalSanctionsForUser(7)).thenReturn(listOf(MessageSanction(UserSanctionType.SUSPEND, ModerationScopeType.GLOBAL, now)))
        `when`(sanctions.activeSanctionsForUser(10, 7)).thenReturn(listOf(MessageSanction(UserSanctionType.MUTE, ModerationScopeType.ROOM, now.plusSeconds(1))))
        assertThrows(MessageModerationRejectedException::class.java) { service.requireAllowedToSend(10, 7) }
        verify(metrics).moderationRejected(ModerationRejectionReason.MUTED, ModerationScopeType.ROOM)
        verifyNoMoreInteractions(metrics)
    }

    @Test
    fun `global sanction precedence is preserved when both scopes restrict the user`() {
        `when`(sanctions.activeGlobalSanctionsForUser(7)).thenReturn(listOf(MessageSanction(UserSanctionType.SUSPEND, ModerationScopeType.GLOBAL, null)))
        `when`(sanctions.activeSanctionsForUser(10, 7)).thenReturn(listOf(MessageSanction(UserSanctionType.BAN, ModerationScopeType.ROOM, null)))
        assertThrows(MessageModerationRejectedException::class.java) { service.requireAllowedToSend(10, 7) }
        verify(metrics).moderationRejected(ModerationRejectionReason.SUSPENDED, ModerationScopeType.GLOBAL)
        verifyNoMoreInteractions(metrics)
    }

    @Test
    fun `sanction lookup failure cannot become a send permission`() {
        `when`(sanctions.activeGlobalSanctionsForUser(7)).thenThrow(IllegalStateException("offline"))
        assertThrows(IllegalStateException::class.java) { service.requireAllowedToSend(10, 7) }
        verify(sanctions, never()).activeSanctionsForUser(10, 7)
        verifyNoInteractions(metrics)
    }
}
