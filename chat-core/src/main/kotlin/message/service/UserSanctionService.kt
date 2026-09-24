package com.chat.core.message.service

import com.chat.core.dto.UserSanctionType
import com.chat.core.message.port.ActiveMessageSanctions
import com.chat.core.message.port.MessagePolicyMetrics
import com.chat.core.message.port.MessageSanction
import com.chat.core.message.port.ModerationRejectionReason
import com.chat.core.message.port.UserSanctionPolicyService
import com.chat.domain.exception.MessageModerationRejectedException
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class UserSanctionService(
    private val userSanctionRepository: ActiveMessageSanctions,
    private val clock: Clock,
    private val metrics: MessagePolicyMetrics,
) : UserSanctionPolicyService {
    override fun requireAllowedToSend(roomId: Long, userId: Long) {
        val now = clock.instant()
        val sanctions = userSanctionRepository.activeGlobalSanctionsForUser(userId) +
            userSanctionRepository.activeSanctionsForUser(roomId, userId)
        val sanction = sanctions
            .asSequence()
            .filter { sanction -> sanction.expiresAt == null || sanction.expiresAt.isAfter(now) }
            .firstOrNull { sanction ->
                sanction.type == UserSanctionType.SUSPEND ||
                    sanction.type == UserSanctionType.MUTE ||
                    sanction.type == UserSanctionType.BAN
            }
            ?: return

        recordRejected(sanction)
        throw MessageModerationRejectedException("user is restricted from sending messages")
    }

    private fun recordRejected(sanction: MessageSanction) {
        val reason = when (sanction.type) {
            UserSanctionType.MUTE -> ModerationRejectionReason.MUTED
            UserSanctionType.BAN -> ModerationRejectionReason.BANNED
            UserSanctionType.SUSPEND -> ModerationRejectionReason.SUSPENDED
        }

        metrics.moderationRejected(reason, sanction.scopeType)
    }
}
