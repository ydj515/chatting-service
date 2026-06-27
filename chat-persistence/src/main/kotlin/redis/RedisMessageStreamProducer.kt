package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.service.MessageStreamMetrics
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageStreamProducer(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val redisProperties: ChatRedisProperties,
    private val keyResolver: MessageStreamKeyResolver,
    private val messageStreamMetrics: MessageStreamMetrics = MessageStreamMetrics.Noop,
) : MessageStreamProducer {
    private val knownStreamsCache = ConcurrentHashMap.newKeySet<String>()

    override fun append(envelope: MessageStreamEnvelope): String {
        val startedAtNanos = System.nanoTime()
        var outcome = OUTCOME_FAILURE
        return try {
            val streamKey = keyResolver.roomStreamKey(envelope.chatRoomId, envelope.streamShard)
            val fields = linkedMapOf(
                FIELD_MESSAGE_ID to envelope.messageId,
                FIELD_CHAT_ROOM_ID to envelope.chatRoomId.toString(),
                FIELD_ROOM_SEQ to envelope.roomSeq.toString(),
                FIELD_STREAM_SHARD to envelope.streamShard.toString(),
                FIELD_PAYLOAD to objectMapper.writeValueAsString(envelope),
            )
            val recordId = appendToStream(streamKey, fields)
                ?: throw IllegalStateException("Redis Streams append returned null: $streamKey")

            if (knownStreamsCache.add(streamKey)) {
                try {
                    redisTemplate.opsForSet().add(redisProperties.streams.knownStreamsKey, streamKey)
                } catch (e: RuntimeException) {
                    knownStreamsCache.remove(streamKey)
                    throw e
                }
            }

            outcome = OUTCOME_SUCCESS
            recordId.value
        } finally {
            messageStreamMetrics.recordAppend(
                streamShard = envelope.streamShard,
                outcome = outcome,
                durationNanos = System.nanoTime() - startedAtNanos,
            )
        }
    }

    private fun appendToStream(streamKey: String, fields: Map<String, String>): RecordId? {
        val streams = redisProperties.streams
        if (streams.maxLen <= 0) {
            return redisTemplate.opsForStream<String, String>().add(streamKey, fields)
        }

        val record = StreamRecords.newRecord()
            .`in`(bytes(streamKey))
            .ofBytes(rawFields(fields))
        val options = RedisStreamCommands.XAddOptions.maxlen(streams.maxLen)
            .approximateTrimming(streams.maxLenApproximate)

        return redisTemplate.execute(RedisCallback<RecordId> { connection ->
            connection.streamCommands().xAdd(record, options)
        })
    }

    private fun rawFields(fields: Map<String, String>): Map<ByteArray, ByteArray> {
        return fields.entries.associateTo(LinkedHashMap()) { (key, value) ->
            bytes(key) to bytes(value)
        }
    }

    private fun bytes(value: String): ByteArray {
        return value.toByteArray(StandardCharsets.UTF_8)
    }

    private companion object {
        const val FIELD_MESSAGE_ID = "messageId"
        const val FIELD_CHAT_ROOM_ID = "chatRoomId"
        const val FIELD_ROOM_SEQ = "roomSeq"
        const val FIELD_STREAM_SHARD = "streamShard"
        const val FIELD_PAYLOAD = "payload"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_SUCCESS = "success"
    }
}
