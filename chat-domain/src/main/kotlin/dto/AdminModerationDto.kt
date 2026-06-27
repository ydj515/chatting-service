package com.chat.domain.dto

import java.time.Instant

enum class ModerationScopeType {
    GLOBAL,
    ROOM,
}

enum class ModerationMatchType {
    CONTAINS,
}

enum class ModerationAction {
    REJECT,
}

enum class UserSanctionType {
    MUTE,
    BAN,
    SUSPEND,
}

data class AdminModerationRuleDto(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val pattern: String,
    val matchType: ModerationMatchType,
    val action: ModerationAction,
    val reason: String?,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminCreateModerationRuleRequest(
    val scopeType: ModerationScopeType,
    val roomId: Long? = null,
    val pattern: String,
    val matchType: ModerationMatchType = ModerationMatchType.CONTAINS,
    val action: ModerationAction = ModerationAction.REJECT,
    val reason: String? = null,
)

data class AdminUpdateModerationRuleRequest(
    val pattern: String? = null,
    val reason: String? = null,
    val enabled: Boolean? = null,
)

data class AdminUserSanctionDto(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val userId: Long,
    val type: UserSanctionType,
    val reason: String?,
    val expiresAt: Instant?,
    val active: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val revokedBy: String?,
    val revokedAt: Instant?,
)

data class AdminCreateUserSanctionRequest(
    val scopeType: ModerationScopeType,
    val roomId: Long? = null,
    val userId: Long,
    val type: UserSanctionType,
    val reason: String? = null,
    val expiresAt: Instant? = null,
)
