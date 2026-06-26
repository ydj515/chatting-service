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
        if (streamKey.startsWith(redisProperties.streams.roomStreamKeyPrefix)) {
            val suffix = streamKey.removePrefix(redisProperties.streams.roomStreamKeyPrefix)
            val hashTagged = HASH_TAGGED_PATTERN.matchEntire(suffix)
            if (hashTagged != null) {
                return "room:${hashTagged.groupValues[1]}"
            }
        }
        // Legacy keys (chat:stream:room:10:shard:N) and non-room keys hash by the full
        // key, so each lands on its own cluster slot. Grouping them per key keeps a
        // multi-key XREADGROUP within a single slot during a hash-tag migration.
        return "stream:$streamKey"
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
