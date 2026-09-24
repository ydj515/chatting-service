package com.chat.core.message.service

import com.chat.core.message.port.AdmissionDecision
import com.chat.core.message.port.AdmissionPolicies
import com.chat.core.message.port.AdmissionRejectionReason
import com.chat.core.message.port.MessageAdmissionPolicyService
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.MessageRateLimiter
import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.model.MemberRole
import org.springframework.stereotype.Service

@Service
class MessageAdmissionService(
    private val policies: AdmissionPolicies,
    private val limiter: MessageRateLimiter,
    private val metrics: MessagePolicyMetrics,
) : MessageAdmissionPolicyService {
    override fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole) {
        val policy = policies.admissionPolicy(roomId)
        if (policy.bypasses(memberRole) || !policy.hasLimit()) return
        when (val decision = limiter.acquire(roomId, senderId, policy)) {
            AdmissionDecision.Allowed -> Unit
            AdmissionDecision.RoomRateLimited -> reject("room rate limit exceeded", AdmissionRejectionReason.ROOM_RATE_LIMITED)
            AdmissionDecision.UserRateLimited -> reject("user rate limit exceeded", AdmissionRejectionReason.USER_RATE_LIMITED)
            AdmissionDecision.SlowModeActive -> reject("slow mode active", AdmissionRejectionReason.SLOW_MODE_ACTIVE)
            is AdmissionDecision.Unavailable -> {
                val reason = if (decision.cause == null) AdmissionRejectionReason.INVALID_LIMITER_RESULT else AdmissionRejectionReason.LIMITER_ERROR
                metrics.admissionRejected(reason)
                throw MessageAdmissionRejectedException("message admission policy unavailable", decision.cause)
            }
        }
    }

    private fun reject(message: String, reason: AdmissionRejectionReason): Nothing {
        metrics.admissionRejected(reason)
        throw MessageAdmissionRejectedException(message)
    }
}
