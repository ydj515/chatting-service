package com.chat.persistence.service

import org.springframework.stereotype.Service

@Service
class CanonicalWriteShardResolver(
    private val roomStorageConfigReader: RoomStorageConfigReader,
) {
    fun resolve(roomId: Long, messageId: String): Int {
        val shardCount = roomStorageConfigReader.currentShardCount(roomId).coerceAtLeast(1)
        return Math.floorMod(messageId.hashCode(), shardCount)
    }
}
