package com.chat.persistence.service

import com.chat.core.admin.port.SuspendedSessions
import com.chat.core.service.SessionControlPublisher
import com.chat.core.service.SessionTokenRevocationStore
import com.chat.persistence.config.SanctionCacheRetryProperties
import com.chat.persistence.repository.SessionRevocationJobRepository
import org.slf4j.LoggerFactory
import org.springframework.core.NestedRuntimeException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

@Service
class SuspendedSessionRevoker(
    private val repository: SessionRevocationJobRepository,
    private val revocationStore: SessionTokenRevocationStore,
    private val sessionControlPublisher: SessionControlPublisher,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val retryProperties: SanctionCacheRetryProperties,
) : SuspendedSessions {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun revokeAfterCommit(userId: Long) {
        val id = UUID.randomUUID().toString()
        // This insert joins the sanction transaction; an insertion failure rolls it back.
        repository.enqueue(id, userId, clock.instant())
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    process(id)
                }
            })
        } else {
            process(id)
        }
    }

    fun retryPending(): Int = try {
        repository.dueIds(clock.instant(), retryProperties.batchSize).count { process(it) }
    } catch (failure: NestedRuntimeException) {
        logger.warn("Unable to read pending session revocations", failure)
        0
    }

    private fun process(id: String): Boolean = try {
        processClaimed(id)
    } catch (failure: NestedRuntimeException) {
        logger.warn("Session revocation remains pending or reclaimable: {}", id, failure)
        false
    }

    private fun processClaimed(id: String): Boolean {
        val now = clock.instant()
        val job = transaction.execute {
            repository.claim(id, UUID.randomUUID().toString(), now, now.plusMillis(retryProperties.leaseMillis))
        } ?: return false
        val delivered = try {
            // Always preserve the original cutoff, even when the retry runs after a new login.
            try {
                revocationStore.revokeUserTokens(job.userId, job.revokedAt)
            } finally {
                sessionControlPublisher.forceLogoutUser(job.userId, "suspended")
            }
            true
        } catch (failure: NestedRuntimeException) {
            logger.warn("Session revocation will retry for user {}", job.userId, failure)
            false
        }
        transaction.executeWithoutResult {
            if (delivered) {
                repository.complete(job)
            } else {
                repository.reschedule(job, clock.instant().plusMillis(retryProperties.retryDelay(job.attempts)))
            }
        }
        return delivered
    }
}
