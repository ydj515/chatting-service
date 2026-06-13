package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import org.springframework.stereotype.Component

@Component
class MessageStreamKeyResolver(
    private val redisProperties: ChatRedisProperties,
) {
    fun roomStreamKey(roomId: Long, streamShard: Int): String {
        return "${redisProperties.streams.roomStreamKeyPrefix}$roomId:shard:$streamShard"
    }

    fun knownStreamsKey(): String = redisProperties.streams.knownStreamsKey

    fun deadLetterStreamKey(consumerGroup: String): String {
        return "${redisProperties.streams.deadLetterStreamKeyPrefix}$consumerGroup"
    }
}
