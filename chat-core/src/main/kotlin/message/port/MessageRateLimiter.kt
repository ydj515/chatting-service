package com.chat.core.message.port

import com.chat.core.message.policy.AdmissionPolicy

interface MessageRateLimiter {
    fun acquire(roomId: Long, senderId: Long, policy: AdmissionPolicy): AdmissionDecision
}

sealed interface AdmissionDecision {
    data object Allowed : AdmissionDecision

    data object RoomRateLimited : AdmissionDecision

    data object UserRateLimited : AdmissionDecision

    data object SlowModeActive : AdmissionDecision

    data class Unavailable(val cause: Exception? = null) : AdmissionDecision
}
