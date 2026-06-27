package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.repository.RoomSeqGapAuditRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class RoomSeqGapAuditWorker(
    private val workerProperties: ChatWorkerProperties,
    private val repository: RoomSeqGapAuditRepository,
    private val metrics: RoomSeqGapAuditMetrics = RoomSeqGapAuditMetrics.Noop,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun poll() {
        val properties = workerProperties.roomSeqGapAudit
        if (!properties.enabled) {
            return
        }

        try {
            val cutoff = clock.instant().minus(properties.lookback)
            val summary = repository.auditSince(cutoff)
            metrics.update(summary)
        } catch (e: RuntimeException) {
            logger.warn("RoomSeq gap audit failed", e)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RoomSeqGapAuditWorker::class.java)
    }
}
