package com.chat.core.room.port

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
