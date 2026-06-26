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
        return "${redisProperties.streams.roomStreamKeyPrefix}{$roomId}:shard:$streamShard"
    }

    fun knownStreamsKey(): String = redisProperties.streams.knownStreamsKey

    fun deadLetterStreamKey(consumerGroup: String): String {
        return "${redisProperties.streams.deadLetterStreamKeyPrefix}$consumerGroup"
    }

    fun streamReadGroupKey(streamKey: String): String {
        val parsed = parseRoomStreamKey(streamKey)
        return if (parsed != null) {
            "room:${parsed.roomId}"
        } else {
            "stream:$streamKey"
        }
    }

    fun parseRoomStreamKey(streamKey: String): RoomStreamKey? {
        if (!streamKey.startsWith(redisProperties.streams.roomStreamKeyPrefix)) {
            return null
        }

        val suffix = streamKey.removePrefix(redisProperties.streams.roomStreamKeyPrefix)
        val match = HASH_TAGGED_PATTERN.matchEntire(suffix) ?: LEGACY_PATTERN.matchEntire(suffix) ?: return null
        val roomId = match.groupValues[1].toLongOrNull() ?: return null
        val streamShard = match.groupValues[2].toIntOrNull() ?: return null
        return RoomStreamKey(roomId = roomId, streamShard = streamShard)
    }

    private companion object {
        val HASH_TAGGED_PATTERN = Regex("""\{(\d+)}:shard:(\d+)""")
        val LEGACY_PATTERN = Regex("""(\d+):shard:(\d+)""")
    }
}
