package com.chat.persistence.service

import com.chat.domain.dto.ChatMessage
import com.chat.domain.dto.ChatMessageBatch
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import com.chat.persistence.redis.RedisMessageBroker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class HotRoomFanoutWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val redisMessageBroker: RedisMessageBroker,
    private val workerProperties: ChatWorkerProperties,
) {
    private val logger = LoggerFactory.getLogger(HotRoomFanoutWorker::class.java)
    private var lastPendingClaimAtMillis = 0L

    fun pollAndFanout(): Int {
        val streamKeys = messageStreamConsumer.listStreamKeys()
        if (streamKeys.isEmpty()) {
            return 0
        }

        val consumerGroup = workerProperties.fanout.consumerGroup
        streamKeys.forEach { streamKey ->
            messageStreamConsumer.ensureConsumerGroup(streamKey, consumerGroup)
        }

        val records = messageStreamConsumer.readNew(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = streamKeys,
            count = workerProperties.fanout.readCount,
        ) + claimPendingIfDue(consumerGroup, streamKeys)

        var broadcastCount = 0
        records.groupBy { it.envelope.chatRoomId }.forEach { (roomId, roomRecords) ->
            val batch = ChatMessageBatch(
                chatRoomId = roomId,
                messages = roomRecords
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

                roomRecords.forEach { record ->
                    messageStreamConsumer.acknowledge(
                        streamKey = record.streamKey,
                        consumerGroup = consumerGroup,
                        recordId = record.recordId,
                    )
                }
            } catch (e: Exception) {
                roomRecords.forEach { record ->
                    handleFailure(record, consumerGroup, e)
                }
            }
        }

        return broadcastCount
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
            id = 0L,
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
