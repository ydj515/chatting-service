package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service

@Service
class RedisMessageStreamProducer(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val redisProperties: ChatRedisProperties,
) : MessageStreamProducer {

    override fun append(envelope: MessageStreamEnvelope): String {
        val streamKey = streamKey(envelope.chatRoomId, envelope.streamShard)
        val fields = linkedMapOf(
            FIELD_MESSAGE_ID to envelope.messageId,
            FIELD_CHAT_ROOM_ID to envelope.chatRoomId.toString(),
            FIELD_ROOM_SEQ to envelope.roomSeq.toString(),
            FIELD_STREAM_SHARD to envelope.streamShard.toString(),
            FIELD_PAYLOAD to objectMapper.writeValueAsString(envelope),
        )
        val recordId = redisTemplate.opsForStream<String, String>().add(streamKey, fields)
            ?: throw IllegalStateException("Redis Streams append returned null: $streamKey")

        return recordId.value
    }

    private fun streamKey(roomId: Long, streamShard: Int): String {
        return "${redisProperties.streams.roomStreamKeyPrefix}$roomId:shard:$streamShard"
    }

    private companion object {
        const val FIELD_MESSAGE_ID = "messageId"
        const val FIELD_CHAT_ROOM_ID = "chatRoomId"
        const val FIELD_ROOM_SEQ = "roomSeq"
        const val FIELD_STREAM_SHARD = "streamShard"
        const val FIELD_PAYLOAD = "payload"
    }
}
