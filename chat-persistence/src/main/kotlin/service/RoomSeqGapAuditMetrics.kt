package com.chat.persistence.service

import com.chat.persistence.repository.RoomSeqGapAuditSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service
class RoomSeqGapAuditMetrics(
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val registeredRegistries = ConcurrentHashMap.newKeySet<MeterRegistry>()
    private val roomCountWithGaps = AtomicLong(0)
    private val missingSequenceCount = AtomicLong(0)
    private val maxGapWidth = AtomicLong(0)
    private val scannedRoomCount = AtomicLong(0)

    fun update(summary: RoomSeqGapAuditSummary) {
        meterRegistryProvider?.ifAvailable { registry ->
            registerGauges(registry)
            roomCountWithGaps.set(summary.roomCountWithGaps.coerceAtLeast(0))
            missingSequenceCount.set(summary.missingSequenceCount.coerceAtLeast(0))
            maxGapWidth.set(summary.maxGapWidth.coerceAtLeast(0))
            scannedRoomCount.set(summary.scannedRoomCount.coerceAtLeast(0))
        }
    }

    private fun registerGauges(registry: MeterRegistry) {
        if (!registeredRegistries.add(registry)) {
            return
        }

        registerGauge(registry, METRIC_ROOMS, roomCountWithGaps)
        registerGauge(registry, METRIC_MISSING_SEQUENCES, missingSequenceCount)
        registerGauge(registry, METRIC_MAX_WIDTH, maxGapWidth)
        registerGauge(registry, METRIC_SCANNED_ROOMS, scannedRoomCount)
    }

    private fun registerGauge(registry: MeterRegistry, name: String, value: AtomicLong) {
        Gauge.builder(name, value) { currentValue -> currentValue.get().toDouble() }
            .register(registry)
    }

    companion object {
        val Noop = RoomSeqGapAuditMetrics()

        private const val METRIC_ROOMS = "chat.room_seq.gap.rooms"
        private const val METRIC_MISSING_SEQUENCES = "chat.room_seq.gap.missing_sequences"
        private const val METRIC_MAX_WIDTH = "chat.room_seq.gap.max_width"
        private const val METRIC_SCANNED_ROOMS = "chat.room_seq.gap.scanned_rooms"
    }
}
