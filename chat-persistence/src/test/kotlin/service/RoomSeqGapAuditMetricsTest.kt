package com.chat.persistence.service

import com.chat.persistence.repository.RoomSeqGapAuditSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class RoomSeqGapAuditMetricsTest {

    @Test
    fun `roomSeq gap audit metric은 aggregate gauge 네 개를 tag 없이 등록한다`() {
        val meterRegistry = SimpleMeterRegistry()
        val metrics = RoomSeqGapAuditMetrics(meterRegistryProvider(meterRegistry))

        metrics.update(
            RoomSeqGapAuditSummary(
                roomCountWithGaps = 2,
                missingSequenceCount = 5,
                maxGapWidth = 3,
                scannedRoomCount = 7,
            ),
        )

        assertEquals(2.0, meterRegistry.find("chat.room_seq.gap.rooms").gauge()?.value())
        assertEquals(5.0, meterRegistry.find("chat.room_seq.gap.missing_sequences").gauge()?.value())
        assertEquals(3.0, meterRegistry.find("chat.room_seq.gap.max_width").gauge()?.value())
        assertEquals(7.0, meterRegistry.find("chat.room_seq.gap.scanned_rooms").gauge()?.value())
        assertTrue(
            meterRegistry.meters
                .filter { meter -> meter.id.name.startsWith("chat.room_seq.gap.") }
                .all { meter -> meter.id.tags.isEmpty() },
        )
    }

    @Test
    fun `roomSeq gap audit metric은 같은 gauge holder의 최신 값으로 갱신한다`() {
        val meterRegistry = SimpleMeterRegistry()
        val metrics = RoomSeqGapAuditMetrics(meterRegistryProvider(meterRegistry))

        metrics.update(RoomSeqGapAuditSummary(2, 5, 3, 7))
        metrics.update(RoomSeqGapAuditSummary(0, 1, 1, 8))

        assertEquals(0.0, meterRegistry.find("chat.room_seq.gap.rooms").gauge()?.value())
        assertEquals(1.0, meterRegistry.find("chat.room_seq.gap.missing_sequences").gauge()?.value())
        assertEquals(1.0, meterRegistry.find("chat.room_seq.gap.max_width").gauge()?.value())
        assertEquals(8.0, meterRegistry.find("chat.room_seq.gap.scanned_rooms").gauge()?.value())
        assertEquals(
            4,
            meterRegistry.meters.count { meter -> meter.id.name.startsWith("chat.room_seq.gap.") },
        )
    }

    private fun meterRegistryProvider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry = meterRegistry
            override fun getObject(vararg args: Any?): MeterRegistry = meterRegistry
            override fun getIfAvailable(): MeterRegistry = meterRegistry
            override fun getIfUnique(): MeterRegistry = meterRegistry
            override fun iterator(): MutableIterator<MeterRegistry> = mutableListOf(meterRegistry).iterator()
            override fun stream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
            override fun orderedStream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
        }
    }
}
