package com.chat.core.room.port

import com.chat.core.room.policy.RoomTrafficSnapshot

interface RoomTrafficStatsService {
    fun recordAccepted(roomId: Long)

    fun activeRoomIds(): Set<Long>

    fun snapshot(roomId: Long): RoomTrafficSnapshot

    object Noop : RoomTrafficStatsService {
        override fun recordAccepted(roomId: Long) = Unit

        override fun activeRoomIds(): Set<Long> = emptySet()

        override fun snapshot(roomId: Long): RoomTrafficSnapshot =
            RoomTrafficSnapshot(
                roomId = roomId,
                roomMessagesPerSecond = 0,
                roomMessagesP95PerSecond = 0,
            )
    }
}
