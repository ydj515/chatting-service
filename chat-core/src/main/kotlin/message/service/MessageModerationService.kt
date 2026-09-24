package com.chat.core.message.service

import com.chat.core.dto.ModerationAction
import com.chat.core.dto.ModerationMatchType
import com.chat.core.message.port.ActiveModerationRules
import com.chat.core.message.port.MessageModerationPolicyService
import com.chat.core.message.port.MessageModerationRule
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.ModerationRejectionReason
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import org.springframework.stereotype.Service

@Service
class MessageModerationService(
    private val moderationRuleRepository: ActiveModerationRules,
    private val metrics: MessagePolicyMetrics,
) : MessageModerationPolicyService {
    override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) {
        if (content.isNullOrBlank()) {
            return
        }

        val matched = moderationRuleRepository.activeRulesForRoom(roomId)
            .firstOrNull { rule -> rule.matches(content) }
            ?: return

        recordRejected(matched)
        throw MessageModerationRejectedException("message blocked by moderation policy")
    }

    private fun MessageModerationRule.matches(content: String): Boolean {
        if (action != ModerationAction.REJECT || matchType != ModerationMatchType.CONTAINS) {
            return false
        }

        return content.contains(pattern, ignoreCase = true)
    }

    private fun recordRejected(rule: MessageModerationRule) {
        metrics.moderationRejected(ModerationRejectionReason.BLOCKED_WORD, rule.scopeType)
    }
}
