package com.chat.persistence.service

import com.chat.domain.service.SessionControlPublisher
import com.chat.domain.service.SessionTokenService
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class SuspendedSessionRevoker(
    private val sessionTokenService: SessionTokenService,
    private val sessionControlPublisher: SessionControlPublisher,
) {
    fun revokeAfterCommit(userId: Long) = afterCommit {
        try {
            sessionTokenService.revokeUserTokens(userId)
        } finally {
            sessionControlPublisher.forceLogoutUser(userId, "suspended")
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action()
                }
            },
        )
    }
}
