package com.chat.persistence.service

import com.chat.core.dto.ModerationScopeType
import com.chat.core.message.port.AdmissionRejectionReason
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.ModerationRejectionReason
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class MicrometerMessagePolicyMetrics(private val registries: ObjectProvider<MeterRegistry>) : MessagePolicyMetrics {
    override fun moderationRejected(reason: ModerationRejectionReason, scope: ModerationScopeType) {
        registries.ifAvailable { registry ->
            Counter.builder("chat.message.moderation.rejected")
                .tag("reason", reason.name.lowercase()).tag("scope", scope.name.lowercase()).tag("action", "reject")
                .register(registry).increment()
        }
    }

    override fun admissionRejected(reason: AdmissionRejectionReason) {
        val tag = when (reason) {
            AdmissionRejectionReason.LIMITER_ERROR -> "redis_error"
            AdmissionRejectionReason.INVALID_LIMITER_RESULT -> "script_error"
            else -> reason.name.lowercase()
        }
        registries.ifAvailable { registry ->
            Counter.builder("chat.message.admission.rejected").tag("reason", tag).register(registry).increment()
        }
    }
}
