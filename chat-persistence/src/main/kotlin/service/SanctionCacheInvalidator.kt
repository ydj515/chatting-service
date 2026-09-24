package com.chat.persistence.service

import com.chat.core.admin.port.SanctionCacheInvalidation
import com.chat.core.dto.ModerationScopeType
import com.chat.persistence.config.SanctionCacheRetryProperties
import com.chat.persistence.repository.SanctionCacheInvalidationRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
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
class SanctionCacheInvalidator(
    private val repository: SanctionCacheInvalidationRepository,
    private val cacheManager: CacheManager,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val properties: SanctionCacheRetryProperties,
) : SanctionCacheInvalidation {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun enqueue(record: UserSanctionRecord) = enqueue(record.scopeType, record.roomId, record.userId)

    override fun enqueue(scopeType: ModerationScopeType, roomId: Long?, userId: Long) {
        val key = when (scopeType) {
            ModerationScopeType.GLOBAL -> "global:$userId"
            ModerationScopeType.ROOM -> "${requireNotNull(roomId)}:$userId"
        }
        val id = UUID.randomUUID().toString()
        repository.enqueue(id, key, clock.instant())
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
        repository.dueIds(clock.instant(), properties.batchSize).count { process(it) }
    } catch (failure: NestedRuntimeException) {
        logger.warn("Unable to read pending sanction cache invalidations", failure)
        0
    }

    internal fun process(id: String): Boolean = try {
        processClaimed(id)
    } catch (failure: NestedRuntimeException) {
        // A failed SQL operation leaves the durable job pending or reclaimable after its lease.
        logger.warn("Unable to complete sanction cache invalidation {}", id, failure)
        false
    }

    private fun processClaimed(id: String): Boolean {
        val now = clock.instant()
        val job = transaction.execute {
            repository.claim(id, UUID.randomUUID().toString(), now, now.plusMillis(properties.leaseMillis))
        } ?: return false
        val evicted = evict(job.cacheKey)
        transaction.executeWithoutResult {
            if (evicted) repository.complete(job) else repository.reschedule(job, clock.instant().plusMillis(properties.retryDelay(job.attempts)))
        }
        return evicted
    }

    private fun evict(key: String): Boolean = try {
        // Redis I/O runs outside the short claim/completion transactions.
        checkNotNull(cacheManager.getCache("userSanctions")) { "userSanctions cache is required" }.evict(key)
        true
    } catch (failure: NestedRuntimeException) {
        logger.warn("Sanction cache unavailable; invalidation will retry", failure)
        false
    } catch (failure: IllegalStateException) {
        logger.warn("Sanction cache unavailable; invalidation will retry", failure)
        false
    }
}
