package com.chat.core.message.policy

import com.chat.domain.model.MemberRole

data class AdmissionPolicy(
    val roomRateLimitPerSecond: Int? = null,
    val userRateLimitPerSecond: Int? = null,
    val slowModeSeconds: Int? = null,
    val moderatorPriority: Boolean = true,
) {
    fun bypasses(role: MemberRole): Boolean = moderatorPriority && (role == MemberRole.OWNER || role == MemberRole.ADMIN)

    fun hasLimit(): Boolean = positive(roomRateLimitPerSecond) || positive(userRateLimitPerSecond) || positive(slowModeSeconds)

    private fun positive(value: Int?): Boolean = value != null && value > 0
}
