package com.chat.persistence.service

import com.chat.domain.dto.ChatMessage
import com.chat.domain.dto.ChatMessageBatch
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import com.chat.persistence.redis.MessageStreamKeyResolver
import com.chat.persistence.redis.RedisMessageBroker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class HotRoomFanoutWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val redisMessageBroker: RedisMessageBroker,
    private val workerProperties: ChatWorkerProperties,
    private val messageStreamKeyResolver: MessageStreamKeyResolver = MessageStreamKeyResolver(ChatRedisProperties()),
    private val fanoutOwnerLeaseService: FanoutOwnerLeaseService = FanoutOwnerLeaseService.Noop,
) {
    private val logger = LoggerFactory.getLogger(HotRoomFanoutWorker::class.java)
    private var lastPendingClaimAtMillis = 0L

    fun pollAndFanout(): Int {
        val streamKeys = messageStreamConsumer.listStreamKeys()
        if (streamKeys.isEmpty()) {
            return 0
        }

        val leasesByStreamKey = acquireOwnerLeases(streamKeys)
        if (leasesByStreamKey.isEmpty()) {
            return 0
        }

        val ownedStreamKeys = leasesByStreamKey.keys
        val consumerGroup = workerProperties.fanout.consumerGroup
        ownedStreamKeys.forEach { streamKey ->
            messageStreamConsumer.ensureConsumerGroup(streamKey, consumerGroup)
        }

        val records = messageStreamConsumer.readNew(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = ownedStreamKeys,
            count = workerProperties.fanout.readCount,
        ) + claimPendingIfDue(consumerGroup, ownedStreamKeys)

        var broadcastCount = 0
        records.groupBy { it.streamKey }.forEach { (streamKey, streamRecords) ->
            val lease = leasesByStreamKey[streamKey] ?: return@forEach
            if (!fanoutOwnerLeaseService.validate(lease, FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH)) {
                logger.warn("Skipped fanout publish for $streamKey because owner lease is no longer valid")
                return@forEach
            }

            val roomId = streamRecords.first().envelope.chatRoomId
            val batch = ChatMessageBatch(
                chatRoomId = roomId,
                messages = streamRecords
                    .map { it.envelope }
                    .sortedBy { it.roomSeq }
                    .map { it.toChatMessage() },
            )

            try {
                redisMessageBroker.broadcastToRoom(
                    roomId = roomId,
                    message = batch,
                )
                broadcastCount += 1

                if (!fanoutOwnerLeaseService.validate(lease, FanoutOwnerLeaseValidationStage.BEFORE_ACK)) {
                    logger.warn("Skipped fanout ack for $streamKey because owner lease is no longer valid")
                    return@forEach
                }

                streamRecords.forEach { record ->
                    messageStreamConsumer.acknowledge(
                        streamKey = record.streamKey,
                        consumerGroup = consumerGroup,
                        recordId = record.recordId,
                    )
                }
            } catch (e: Exception) {
                if (!fanoutOwnerLeaseService.validate(lease, FanoutOwnerLeaseValidationStage.BEFORE_ACK)) {
                    logger.warn("Skipped fanout failure handling for $streamKey because owner lease is no longer valid")
                    return@forEach
                }
                streamRecords.forEach { record ->
                    handleFailure(record, consumerGroup, e)
                }
            }
        }

        return broadcastCount
    }

    private fun acquireOwnerLeases(streamKeys: Set<String>): Map<String, FanoutOwnerLease> {
        return streamKeys.mapNotNull { streamKey ->
            val parsed = messageStreamKeyResolver.parseRoomStreamKey(streamKey)
            if (parsed == null) {
                logger.warn("Skipping fanout stream with unexpected key format: $streamKey")
                return@mapNotNull null
            }

            val lease = fanoutOwnerLeaseService.acquire(
                roomId = parsed.roomId,
                streamShard = parsed.streamShard,
            ) ?: return@mapNotNull null

            streamKey to lease
        }.toMap()
    }

    private fun claimPendingIfDue(
        consumerGroup: String,
        streamKeys: Set<String>,
    ): List<MessageStreamRecord> {
        val now = System.currentTimeMillis()
        val intervalMillis = workerProperties.fanout.claimIntervalMillis
        if (lastPendingClaimAtMillis != 0L && now - lastPendingClaimAtMillis < intervalMillis) {
            return emptyList()
        }
        lastPendingClaimAtMillis = now

        return messageStreamConsumer.claimPending(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = streamKeys,
            count = workerProperties.fanout.readCount,
            minIdleMillis = workerProperties.fanout.minIdleMillis,
        )
    }

    private fun handleFailure(
        record: MessageStreamRecord,
        consumerGroup: String,
        throwable: Exception,
    ) {
        val reason = throwable.message ?: throwable.javaClass.simpleName
        if (record.deliveryCount >= workerProperties.fanout.maxDeliveryCount) {
            messageStreamConsumer.sendToDeadLetter(record, consumerGroup, reason)
            messageStreamConsumer.acknowledge(
                streamKey = record.streamKey,
                consumerGroup = consumerGroup,
                recordId = record.recordId,
            )
            logger.warn("Moved fanout message ${record.recordId} to dead letter stream: $reason")
            return
        }

        logger.warn("Fanout failed for ${record.recordId}; record remains pending: $reason")
    }

    private fun MessageStreamEnvelope.toChatMessage(): ChatMessage {
        return ChatMessage(
            id = roomSeq,
            messageId = messageId,
            clientMessageId = clientMessageId,
            content = content ?: "",
            messageType = messageType,
            senderId = senderId,
            senderName = senderName,
            sequenceNumber = sequenceNumber,
            roomSeq = roomSeq,
            streamShard = streamShard,
            writeShard = writeShard,
            fanoutShard = fanoutShard,
            chatRoomId = chatRoomId,
            timestamp = createdAt,
        )
    }
}
