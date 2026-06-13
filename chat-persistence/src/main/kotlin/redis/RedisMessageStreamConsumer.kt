package com.chat.persistence.redis

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service

@Service
class RedisMessageStreamConsumer(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val keyResolver: MessageStreamKeyResolver,
) : MessageStreamConsumer {

    override fun listStreamKeys(): Set<String> {
        return redisTemplate.opsForSet().members(keyResolver.knownStreamsKey()) ?: emptySet()
    }

    override fun ensureConsumerGroup(streamKey: String, consumerGroup: String) {
        try {
            redisTemplate.opsForStream<String, String>()
                .createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup)
        } catch (e: RuntimeException) {
            if (e.message?.contains("BUSYGROUP", ignoreCase = true) == true) {
                return
            }
            throw e
        }
    }

    override fun readNew(
        consumerGroup: String,
        consumerName: String,
        streamKeys: Set<String>,
        count: Long,
    ): List<MessageStreamRecord> {
        if (streamKeys.isEmpty()) {
            return emptyList()
        }

        val offsets = streamKeys
            .map { StreamOffset.create(it, ReadOffset.lastConsumed()) }
            .toTypedArray()
        val records = redisTemplate.opsForStream<String, String>().read(
            Consumer.from(consumerGroup, consumerName),
            StreamReadOptions.empty().count(count),
            *offsets,
        ) ?: emptyList()

        return records.mapNotNull { record ->
            val payload = record.value[FIELD_PAYLOAD] ?: return@mapNotNull null
            MessageStreamRecord(
                streamKey = record.requiredStream,
                recordId = record.id.value,
                envelope = objectMapper.readValue(payload, MessageStreamEnvelope::class.java),
            )
        }
    }

    override fun acknowledge(streamKey: String, consumerGroup: String, recordId: String) {
        redisTemplate.opsForStream<String, String>().acknowledge(streamKey, consumerGroup, recordId)
    }

    private companion object {
        const val FIELD_PAYLOAD = "payload"
    }
}
