package com.chat.core.message.port

import com.chat.core.dto.ModerationAction
import com.chat.core.dto.ModerationMatchType
import com.chat.core.dto.ModerationScopeType
import com.chat.core.dto.UserSanctionType
import com.chat.core.message.policy.AdmissionPolicy
import java.time.Instant

interface ActiveMessageSanctions {
    fun activeGlobalSanctionsForUser(userId: Long): List<MessageSanction>

    fun activeSanctionsForUser(roomId: Long, userId: Long): List<MessageSanction>
}

data class MessageSanction(val type: UserSanctionType, val scopeType: ModerationScopeType, val expiresAt: Instant?)

interface ActiveModerationRules {
    fun activeRulesForRoom(roomId: Long): List<MessageModerationRule>
}

data class MessageModerationRule(val pattern: String, val matchType: ModerationMatchType, val action: ModerationAction, val scopeType: ModerationScopeType)

interface AdmissionPolicies {
    fun admissionPolicy(roomId: Long): AdmissionPolicy
}
