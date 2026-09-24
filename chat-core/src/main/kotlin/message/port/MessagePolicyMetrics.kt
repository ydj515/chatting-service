package com.chat.core.message.port

import com.chat.core.dto.ModerationScopeType

interface MessagePolicyMetrics {
    fun moderationRejected(reason: ModerationRejectionReason, scope: ModerationScopeType)

    fun admissionRejected(reason: AdmissionRejectionReason)
}

enum class ModerationRejectionReason { BLOCKED_WORD, MUTED, BANNED, SUSPENDED }

enum class AdmissionRejectionReason { ROOM_RATE_LIMITED, USER_RATE_LIMITED, SLOW_MODE_ACTIVE, LIMITER_ERROR, INVALID_LIMITER_RESULT }
