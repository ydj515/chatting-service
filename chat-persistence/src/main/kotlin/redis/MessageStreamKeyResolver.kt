package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import org.springframework.stereotype.Component

@Component
class MessageStreamKeyResolver(
    private val redisProperties: ChatRedisProperties,
) {
    data class RoomStreamKey(
        val roomId: Long,
        val streamShard: Int,
    )

    fun roomStreamKey(roomId: Long, streamShard: Int): String {
        return "${redisProperties.streams.roomStreamKeyPrefix}$roomId:shard:$streamShard"
    }

    fun knownStreamsKey(): String = redisProperties.streams.knownStreamsKey

    fun deadLetterStreamKey(consumerGroup: String): String {
        return "${redisProperties.streams.deadLetterStreamKeyPrefix}$consumerGroup"
    }

    fun parseRoomStreamKey(streamKey: String): RoomStreamKey? {
        if (!streamKey.startsWith(redisProperties.streams.roomStreamKeyPrefix)) {
            return null
        }

        val suffix = streamKey.removePrefix(redisProperties.streams.roomStreamKeyPrefix)
        val parts = suffix.split(":shard:", limit = 2)
        if (parts.size != 2) {
            return null
        }

        val roomId = parts[0].toLongOrNull() ?: return null
        val streamShard = parts[1].toIntOrNull() ?: return null
        return RoomStreamKey(roomId = roomId, streamShard = streamShard)
    }
}
