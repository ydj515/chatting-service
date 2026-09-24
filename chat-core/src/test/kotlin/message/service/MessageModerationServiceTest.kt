package com.chat.core.message.service

import com.chat.core.dto.ModerationAction
import com.chat.core.dto.ModerationMatchType
import com.chat.core.dto.ModerationScopeType
import com.chat.core.message.port.ActiveModerationRules
import com.chat.core.message.port.MessageModerationRule
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.ModerationRejectionReason
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class MessageModerationServiceTest {
    private val rules = mock(ActiveModerationRules::class.java)
    private val metrics = mock(MessagePolicyMetrics::class.java)
    private val service = MessageModerationService(rules, metrics)

    @Test
    fun `system messages cannot bypass case insensitive blocked words`() {
        `when`(rules.activeRulesForRoom(10)).thenReturn(listOf(MessageModerationRule("blocked", ModerationMatchType.CONTAINS, ModerationAction.REJECT, ModerationScopeType.GLOBAL)))
        assertThrows(MessageModerationRejectedException::class.java) { service.requireAllowed(10, 7, "BLOCKED text", MessageType.SYSTEM) }
        verify(metrics).moderationRejected(ModerationRejectionReason.BLOCKED_WORD, ModerationScopeType.GLOBAL)
    }

    @Test
    fun `empty content avoids unnecessary rule queries`() {
        service.requireAllowed(10, 7, null, MessageType.TEXT)
        service.requireAllowed(10, 7, "  ", MessageType.SYSTEM)
        verifyNoInteractions(rules, metrics)
    }
}
