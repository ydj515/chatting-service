package com.chat.persistence.service

import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Clock

interface UserSanctionPolicyService {
    fun requireAllowedToSend(roomId: Long, userId: Long)

    object Noop : UserSanctionPolicyService {
        override fun requireAllowedToSend(roomId: Long, userId: Long) = Unit
    }
}

@Service
class UserSanctionService(
    private val userSanctionRepository: UserSanctionJdbcRepository,
    private val clock: Clock,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : UserSanctionPolicyService {

    override fun requireAllowedToSend(roomId: Long, userId: Long) {
        val now = clock.instant()
        val sanction = userSanctionRepository.activeSanctionsForUser(roomId, userId)
            .asSequence()
            .filter { sanction -> sanction.expiresAt == null || sanction.expiresAt.isAfter(now) }
            .firstOrNull { sanction -> sanction.type == UserSanctionType.MUTE || sanction.type == UserSanctionType.BAN }
            ?: return

        recordRejected(sanction)
        throw MessageModerationRejectedException("user is restricted from sending messages")
    }

    private fun recordRejected(sanction: UserSanctionRecord) {
        val reason = when (sanction.type) {
            UserSanctionType.MUTE -> "muted"
            UserSanctionType.BAN -> "banned"
            UserSanctionType.SUSPEND_RESERVED -> "suspend_reserved"
        }

        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.message.moderation.rejected")
                .tag("reason", reason)
                .tag("scope", "room")
                .tag("action", "reject")
                .register(registry)
                .increment()
        }
    }
}
