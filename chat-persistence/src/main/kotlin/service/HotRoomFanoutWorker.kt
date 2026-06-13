package com.chat.persistence.service

import com.chat.domain.dto.ChatMessage
import com.chat.domain.dto.ChatMessageBatch
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.RedisMessageBroker
import org.springframework.stereotype.Service

@Service
class HotRoomFanoutWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val redisMessageBroker: RedisMessageBroker,
    private val workerProperties: ChatWorkerProperties,
) {

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
        )

        var broadcastCount = 0
        records.groupBy { it.envelope.chatRoomId }.forEach { (roomId, roomRecords) ->
            val batch = ChatMessageBatch(
                chatRoomId = roomId,
                messages = roomRecords
                    .map { it.envelope }
                    .sortedBy { it.roomSeq }
                    .map { it.toChatMessage() },
            )

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
        }

        return broadcastCount
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
