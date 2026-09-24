package com.chat.core.room.service

import com.chat.core.room.policy.RoomTrafficSnapshot
import com.chat.core.room.port.RoomPolicySignalProvider
import com.chat.core.room.port.RoomPolicySignals
import com.chat.core.room.port.RoomTrafficStatsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RoomPolicyWorker(
    private val roomTrafficStatsService: RoomTrafficStatsService,
    private val roomPolicyAutoDowngradeService: RoomPolicyAutoDowngradeService,
    private val roomPolicySignalProvider: RoomPolicySignalProvider,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun pollAndApply(): Int {
        val roomIds = roomTrafficStatsService.activeRoomIds()
        var appliedCount = 0
        roomIds.forEach { roomId ->
            try {
                roomPolicyAutoDowngradeService.applyDowngradePolicy(
                    roomTrafficStatsService.snapshot(roomId)
                        .withSignals(roomPolicySignalProvider.signals(roomId)),
                )
                appliedCount += 1
            } catch (e: Exception) {
                logger.warn("Failed to apply room policy for roomId={}", roomId, e)
            }
        }
        return appliedCount
    }

    private fun RoomTrafficSnapshot.withSignals(signals: RoomPolicySignals): RoomTrafficSnapshot =
        copy(
            writerLagMillis = signals.writerLagMillis,
            fanoutLagMillis = signals.fanoutLagMillis,
            gatewaySendQueueDepth = signals.gatewaySendQueueDepth,
        )
}
