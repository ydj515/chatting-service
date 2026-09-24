package com.chat.persistence.repository

import com.chat.core.message.policy.AdmissionPolicy
import com.chat.core.message.port.ActiveMessageSanctions
import com.chat.core.message.port.ActiveModerationRules
import com.chat.core.message.port.AdmissionPolicies
import com.chat.core.message.port.MessageModerationRule
import com.chat.core.message.port.MessageSanction
import com.chat.persistence.service.RoomAdmissionPolicyReader
import org.springframework.stereotype.Component

@Component
class MessagePolicyReadAdapter(
    private val sanctions: UserSanctionJdbcRepository,
    private val rules: ModerationRuleJdbcRepository,
    private val admission: RoomAdmissionPolicyReader,
) : ActiveMessageSanctions, ActiveModerationRules, AdmissionPolicies {
    override fun activeGlobalSanctionsForUser(userId: Long): List<MessageSanction> = sanctions.activeGlobalSanctionsForUser(userId).map { it.toPolicy() }

    override fun activeSanctionsForUser(roomId: Long, userId: Long): List<MessageSanction> = sanctions.activeSanctionsForUser(roomId, userId).map { it.toPolicy() }

    override fun activeRulesForRoom(roomId: Long): List<MessageModerationRule> = rules.activeRulesForRoom(roomId).map {
        MessageModerationRule(it.pattern, it.matchType, it.action, it.scopeType)
    }

    override fun admissionPolicy(roomId: Long): AdmissionPolicy = admission.admissionPolicy(roomId).let {
        AdmissionPolicy(it.roomRateLimitPerSecond, it.userRateLimitPerSecond, it.slowModeSeconds, it.moderatorPriority)
    }

    private fun UserSanctionRecord.toPolicy() = MessageSanction(type, scopeType, expiresAt)
}
