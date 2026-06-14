package com.chat.persistence.service

interface RoomStorageConfigReader {
    fun currentShardCount(roomId: Long): Int
}
