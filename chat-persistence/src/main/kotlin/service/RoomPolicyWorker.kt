package com.chat.persistence.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class RoomPolicySignals(
    val writerLagMillis: Long = 0,
    val fanoutLagMillis: Long = 0,
    val gatewaySendQueueDepth: Int = 0,
)

interface RoomPolicySignalProvider {
    fun signals(roomId: Long): RoomPolicySignals

    object Noop : RoomPolicySignalProvider {
        override fun signals(roomId: Long): RoomPolicySignals = RoomPolicySignals()
    }
}

@Service
class NoopRoomPolicySignalProvider : RoomPolicySignalProvider {
    override fun signals(roomId: Long): RoomPolicySignals = RoomPolicySignals()
}

@Service
class RoomPolicyWorker(
    private val roomTrafficStatsService: RoomTrafficStatsService,
    private val roomPolicyAutoDowngradeService: RoomPolicyAutoDowngradeService,
    private val roomPolicySignalProvider: RoomPolicySignalProvider = RoomPolicySignalProvider.Noop,
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

    private fun RoomTrafficSnapshot.withSignals(signals: RoomPolicySignals): RoomTrafficSnapshot {
        return copy(
            writerLagMillis = signals.writerLagMillis,
            fanoutLagMillis = signals.fanoutLagMillis,
            gatewaySendQueueDepth = signals.gatewaySendQueueDepth,
        )
    }
}
