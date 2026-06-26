package com.chat.persistence.service

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.ModerationRuleRecord
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

interface MessageModerationPolicyService {
    fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType)

    object Noop : MessageModerationPolicyService {
        override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) = Unit
    }
}

@Service
class MessageModerationService(
    private val moderationRuleRepository: ModerationRuleJdbcRepository,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
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

    private fun ModerationRuleRecord.matches(content: String): Boolean {
        if (action != ModerationAction.REJECT || matchType != ModerationMatchType.CONTAINS) {
            return false
        }

        return content.contains(pattern, ignoreCase = true)
    }

    private fun recordRejected(rule: ModerationRuleRecord) {
        val scope = if (rule.scopeType == ModerationScopeType.GLOBAL) "global" else "room"
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.message.moderation.rejected")
                .tag("reason", "blocked_word")
                .tag("scope", scope)
                .tag("action", "reject")
                .register(registry)
                .increment()
        }
    }
}
