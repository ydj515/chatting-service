package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.repository.RoomSeqGapAuditRepository
import com.chat.persistence.repository.RoomSeqGapAuditSummary
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class RoomSeqGapAuditWorkerTest {

    @Test
    fun `enabled이면 lookback cutoff로 gap audit을 수행하고 metric을 갱신한다`() {
        val repository = mock(RoomSeqGapAuditRepository::class.java)
        val metrics = mock(RoomSeqGapAuditMetrics::class.java)
        val summary = RoomSeqGapAuditSummary(
            roomCountWithGaps = 2,
            missingSequenceCount = 5,
            maxGapWidth = 3,
            scannedRoomCount = 7,
        )
        val clock = Clock.fixed(Instant.parse("2026-06-27T12:00:00Z"), ZoneOffset.UTC)
        `when`(repository.auditSince(LocalDateTime.parse("2026-06-27T10:00:00"))).thenReturn(summary)
        val worker = RoomSeqGapAuditWorker(
            workerProperties = ChatWorkerProperties(
                roomSeqGapAudit = ChatWorkerProperties.RoomSeqGapAudit(
                    enabled = true,
                    lookback = Duration.ofHours(2),
                ),
            ),
            repository = repository,
            metrics = metrics,
            clock = clock,
        )

        worker.poll()

        verify(repository).auditSince(LocalDateTime.parse("2026-06-27T10:00:00"))
        verify(metrics).update(summary)
    }

    @Test
    fun `disabled이면 gap audit을 수행하지 않는다`() {
        val repository = mock(RoomSeqGapAuditRepository::class.java)
        val metrics = mock(RoomSeqGapAuditMetrics::class.java)
        val worker = RoomSeqGapAuditWorker(
            workerProperties = ChatWorkerProperties(
                roomSeqGapAudit = ChatWorkerProperties.RoomSeqGapAudit(enabled = false),
            ),
            repository = repository,
            metrics = metrics,
            clock = Clock.fixed(Instant.parse("2026-06-27T12:00:00Z"), ZoneOffset.UTC),
        )

        worker.poll()

        verifyNoInteractions(repository)
        verifyNoInteractions(metrics)
    }

    @Test
    fun `repository 실패는 worker 밖으로 전파하지 않고 metric을 갱신하지 않는다`() {
        val repository = mock(RoomSeqGapAuditRepository::class.java)
        val metrics = mock(RoomSeqGapAuditMetrics::class.java)
        `when`(repository.auditSince(LocalDateTime.parse("2026-06-27T11:00:00")))
            .thenThrow(RuntimeException("db down"))
        val worker = RoomSeqGapAuditWorker(
            workerProperties = ChatWorkerProperties(),
            repository = repository,
            metrics = metrics,
            clock = Clock.fixed(Instant.parse("2026-06-27T12:00:00Z"), ZoneOffset.UTC),
        )

        assertDoesNotThrow {
            worker.poll()
        }

        verify(repository).auditSince(LocalDateTime.parse("2026-06-27T11:00:00"))
        verifyNoInteractions(metrics)
    }
}
