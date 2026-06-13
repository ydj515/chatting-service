package com.chat.persistence.redis

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageStreamConsumer(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val keyResolver: MessageStreamKeyResolver,
) : MessageStreamConsumer {
    private val ensuredConsumerGroups = ConcurrentHashMap.newKeySet<String>()

    override fun listStreamKeys(): Set<String> {
        return redisTemplate.opsForSet().members(keyResolver.knownStreamsKey()) ?: emptySet()
    }

    override fun ensureConsumerGroup(streamKey: String, consumerGroup: String) {
        val cacheKey = "$streamKey:$consumerGroup"
        if (!ensuredConsumerGroups.add(cacheKey)) {
            return
        }

        try {
            redisTemplate.opsForStream<String, String>()
                .createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup)
        } catch (e: RuntimeException) {
            if (e.message?.contains("BUSYGROUP", ignoreCase = true) == true) {
                return
            }
            ensuredConsumerGroups.remove(cacheKey)
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

    override fun claimPending(
        consumerGroup: String,
        consumerName: String,
        streamKeys: Set<String>,
        count: Long,
        minIdleMillis: Long,
    ): List<MessageStreamRecord> {
        if (streamKeys.isEmpty()) {
            return emptyList()
        }

        return streamKeys.flatMap { streamKey ->
            val pendingMessages = redisTemplate.opsForStream<String, String>()
                .pending(streamKey, consumerGroup, Range.unbounded<String>(), count)
            val claimable = pendingMessages
                .asSequence()
                .filter { it.elapsedTimeSinceLastDelivery.toMillis() >= minIdleMillis }
                .toList()

            if (claimable.isEmpty()) {
                return@flatMap emptyList()
            }

            val deliveryCountsById = claimable.associate { it.idAsString to it.totalDeliveryCount }
            val ids: Array<RecordId> = claimable.map { it.id }.toTypedArray()
            val claimedRecords = redisTemplate.opsForStream<String, String>().claim(
                streamKey,
                consumerGroup,
                consumerName,
                Duration.ofMillis(minIdleMillis),
                *ids,
            )

            claimedRecords.mapNotNull { record ->
                val payload = record.value[FIELD_PAYLOAD] ?: return@mapNotNull null
                MessageStreamRecord(
                    streamKey = record.requiredStream,
                    recordId = record.id.value,
                    envelope = objectMapper.readValue(payload, MessageStreamEnvelope::class.java),
                    deliveryCount = deliveryCountsById[record.id.value] ?: 1,
                )
            }
        }
    }

    override fun acknowledge(streamKey: String, consumerGroup: String, recordId: String) {
        redisTemplate.opsForStream<String, String>().acknowledge(streamKey, consumerGroup, recordId)
    }

    override fun sendToDeadLetter(
        record: MessageStreamRecord,
        consumerGroup: String,
        reason: String,
    ) {
        val fields = linkedMapOf(
            FIELD_SOURCE_STREAM_KEY to record.streamKey,
            FIELD_SOURCE_RECORD_ID to record.recordId,
            FIELD_CONSUMER_GROUP to consumerGroup,
            FIELD_DELIVERY_COUNT to record.deliveryCount.toString(),
            FIELD_REASON to reason,
            FIELD_PAYLOAD to objectMapper.writeValueAsString(record.envelope),
        )
        redisTemplate.opsForStream<String, String>().add(
            keyResolver.deadLetterStreamKey(consumerGroup),
            fields,
        )
    }

    private companion object {
        const val FIELD_PAYLOAD = "payload"
        const val FIELD_SOURCE_STREAM_KEY = "sourceStreamKey"
        const val FIELD_SOURCE_RECORD_ID = "sourceRecordId"
        const val FIELD_CONSUMER_GROUP = "consumerGroup"
        const val FIELD_DELIVERY_COUNT = "deliveryCount"
        const val FIELD_REASON = "reason"
    }
}
