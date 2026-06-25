package com.chat.persistence.service

data class RoomShardConfig(
    val writeShardCount: Int = 1,
    val fanoutShardCount: Int = 1,
)

interface RoomStorageConfigReader {
    fun currentShardCount(roomId: Long): Int

    fun shardConfig(roomId: Long): RoomShardConfig
}
