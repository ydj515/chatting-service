package com.chat.core.message.service

import com.chat.core.message.policy.AdmissionPolicy
import com.chat.core.message.port.AdmissionDecision
import com.chat.core.message.port.AdmissionPolicies
import com.chat.core.message.port.AdmissionRejectionReason
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.MessageRateLimiter
import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.model.MemberRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class MessageAdmissionServiceTest {
    private val policies = mock(AdmissionPolicies::class.java)
    private val limiter = mock(MessageRateLimiter::class.java)
    private val metrics = mock(MessagePolicyMetrics::class.java)
    private val service = MessageAdmissionService(policies, limiter, metrics)
    private val policy = AdmissionPolicy(roomRateLimitPerSecond = 1)

    @Test
    fun `privileged roles and disabled limits never consume permits`() {
        `when`(policies.admissionPolicy(10)).thenReturn(policy)
        service.requireAllowed(10, 7, MemberRole.OWNER)
        service.requireAllowed(10, 7, MemberRole.ADMIN)
        `when`(policies.admissionPolicy(10)).thenReturn(AdmissionPolicy())
        service.requireAllowed(10, 7, MemberRole.MEMBER)
        verifyNoInteractions(limiter, metrics)
    }

    @Test
    fun `limiter rejections preserve reason and error contract`() {
        `when`(policies.admissionPolicy(10)).thenReturn(policy)
        val cases = listOf(
            Triple(AdmissionDecision.RoomRateLimited, AdmissionRejectionReason.ROOM_RATE_LIMITED, "room rate limit exceeded"),
            Triple(AdmissionDecision.UserRateLimited, AdmissionRejectionReason.USER_RATE_LIMITED, "user rate limit exceeded"),
            Triple(AdmissionDecision.SlowModeActive, AdmissionRejectionReason.SLOW_MODE_ACTIVE, "slow mode active"),
        )
        cases.forEach { (decision, reason, message) ->
            `when`(limiter.acquire(10, 7, policy)).thenReturn(decision)
            val failure = assertThrows(MessageAdmissionRejectedException::class.java) { service.requireAllowed(10, 7, MemberRole.MEMBER) }
            assertEquals(message, failure.message)
            verify(metrics).admissionRejected(reason)
        }
    }

    @Test
    fun `unavailable and malformed limiter outcomes fail closed`() {
        `when`(policies.admissionPolicy(10)).thenReturn(policy)
        val cause = IllegalStateException("offline")
        `when`(limiter.acquire(10, 7, policy)).thenReturn(AdmissionDecision.Unavailable(cause))
        val failure = assertThrows(MessageAdmissionRejectedException::class.java) { service.requireAllowed(10, 7, MemberRole.MEMBER) }
        assertSame(cause, failure.cause)
        verify(metrics).admissionRejected(AdmissionRejectionReason.LIMITER_ERROR)
        `when`(limiter.acquire(10, 7, policy)).thenReturn(AdmissionDecision.Unavailable())
        assertThrows(MessageAdmissionRejectedException::class.java) { service.requireAllowed(10, 7, MemberRole.MEMBER) }
        verify(metrics).admissionRejected(AdmissionRejectionReason.INVALID_LIMITER_RESULT)
    }

    @Test
    fun `an allowed permit does not emit a rejection metric`() {
        `when`(policies.admissionPolicy(10)).thenReturn(policy)
        `when`(limiter.acquire(10, 7, policy)).thenReturn(AdmissionDecision.Allowed)
        service.requireAllowed(10, 7, MemberRole.MEMBER)
        verifyNoInteractions(metrics)
    }
}
