package com.chat.persistence.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class RoomPolicyWorkerTest {

    @Test
    fun `active room snapshot마다 자동 downgrade policy를 적용한다`() {
        val trafficStatsService = StaticRoomTrafficStatsService(
            snapshots = mapOf(
                10L to RoomTrafficSnapshot(
                    roomId = 10L,
                    roomMessagesPerSecond = 5000,
                    roomMessagesP95PerSecond = 5000,
                ),
                11L to RoomTrafficSnapshot(
                    roomId = 11L,
                    roomMessagesPerSecond = 100,
                    roomMessagesP95PerSecond = 100,
                ),
            ),
        )
        val autoDowngradeService = mock(RoomPolicyAutoDowngradeService::class.java)
        `when`(autoDowngradeService.applyDowngradePolicy(trafficStatsService.snapshot(10L)))
            .thenReturn(normalPolicy(10L))
        `when`(autoDowngradeService.applyDowngradePolicy(trafficStatsService.snapshot(11L)))
            .thenReturn(normalPolicy(11L))
        val worker = RoomPolicyWorker(
            roomTrafficStatsService = trafficStatsService,
            roomPolicyAutoDowngradeService = autoDowngradeService,
        )

        val appliedCount = worker.pollAndApply()

        assertEquals(2, appliedCount)
        verify(autoDowngradeService).applyDowngradePolicy(trafficStatsService.snapshot(10L))
        verify(autoDowngradeService).applyDowngradePolicy(trafficStatsService.snapshot(11L))
    }

    @Test
    fun `room policy worker는 traffic snapshot에 system lag signal을 합성해 downgrade를 판단한다`() {
        val trafficStatsService = StaticRoomTrafficStatsService(
            snapshots = mapOf(
                10L to RoomTrafficSnapshot(
                    roomId = 10L,
                    roomMessagesPerSecond = 100,
                    roomMessagesP95PerSecond = 100,
                ),
            ),
        )
        val signalProvider = StaticRoomPolicySignalProvider(
            signals = mapOf(
                10L to RoomPolicySignals(
                    writerLagMillis = 3_500,
                    fanoutLagMillis = 2_000,
                    gatewaySendQueueDepth = 64,
                ),
            ),
        )
        val autoDowngradeService = mock(RoomPolicyAutoDowngradeService::class.java)
        val expectedSnapshot = RoomTrafficSnapshot(
            roomId = 10L,
            roomMessagesPerSecond = 100,
            roomMessagesP95PerSecond = 100,
            writerLagMillis = 3_500,
            fanoutLagMillis = 2_000,
            gatewaySendQueueDepth = 64,
        )
        `when`(autoDowngradeService.applyDowngradePolicy(expectedSnapshot))
            .thenReturn(normalPolicy(10L))
        val worker = RoomPolicyWorker(
            roomTrafficStatsService = trafficStatsService,
            roomPolicyAutoDowngradeService = autoDowngradeService,
            roomPolicySignalProvider = signalProvider,
        )

        val appliedCount = worker.pollAndApply()

        assertEquals(1, appliedCount)
        verify(autoDowngradeService).applyDowngradePolicy(expectedSnapshot)
    }

    private class StaticRoomTrafficStatsService(
        private val snapshots: Map<Long, RoomTrafficSnapshot>,
    ) : RoomTrafficStatsService {

        override fun recordAccepted(roomId: Long) = Unit

        override fun activeRoomIds(): Set<Long> = snapshots.keys

        override fun snapshot(roomId: Long): RoomTrafficSnapshot {
            return snapshots.getValue(roomId)
        }
    }

    private class StaticRoomPolicySignalProvider(
        private val signals: Map<Long, RoomPolicySignals>,
    ) : RoomPolicySignalProvider {

        override fun signals(roomId: Long): RoomPolicySignals {
            return signals.getValue(roomId)
        }
    }

    private fun normalPolicy(roomId: Long): RoomHeatPolicy {
        return RoomHeatPolicy(
            roomId = roomId,
            heatLevel = RoomHeatLevel.NORMAL,
            liveFeedMaxMessages = 1000,
            liveFeedMaxAgeSeconds = 60,
            roomRateLimitPerSecond = null,
            slowModeSeconds = null,
        )
    }
}
