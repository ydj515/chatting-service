package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessageWriterWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val messageWritePort: MessageWritePort,
    private val workerProperties: ChatWorkerProperties,
) {
    private val logger = LoggerFactory.getLogger(MessageWriterWorker::class.java)
    private var lastPendingClaimAtMillis = 0L

    fun pollAndWrite(): Int {
        val streamKeys = messageStreamConsumer.listStreamKeys()
        if (streamKeys.isEmpty()) {
            return 0
        }

        val consumerGroup = workerProperties.writer.consumerGroup
        streamKeys.forEach { streamKey ->
            messageStreamConsumer.ensureConsumerGroup(streamKey, consumerGroup)
        }

        val records = messageStreamConsumer.readNew(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = streamKeys,
            count = workerProperties.writer.readCount,
        ) + claimPendingIfDue(consumerGroup, streamKeys)

        return writeBatch(records, consumerGroup)
    }

    private fun claimPendingIfDue(
        consumerGroup: String,
        streamKeys: Set<String>,
    ): List<MessageStreamRecord> {
        val now = System.currentTimeMillis()
        val intervalMillis = workerProperties.writer.claimIntervalMillis
        if (lastPendingClaimAtMillis != 0L && now - lastPendingClaimAtMillis < intervalMillis) {
            return emptyList()
        }
        lastPendingClaimAtMillis = now

        return messageStreamConsumer.claimPending(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = streamKeys,
            count = workerProperties.writer.readCount,
            minIdleMillis = workerProperties.writer.minIdleMillis,
        )
    }

    private fun handleFailure(
        record: MessageStreamRecord,
        consumerGroup: String,
        throwable: Exception,
    ) {
        val reason = throwable.message ?: throwable.javaClass.simpleName
        if (record.deliveryCount >= workerProperties.writer.maxDeliveryCount) {
            messageStreamConsumer.sendToDeadLetter(record, consumerGroup, reason)
            messageStreamConsumer.acknowledge(
                streamKey = record.streamKey,
                consumerGroup = consumerGroup,
                recordId = record.recordId,
            )
            logger.warn("Moved message ${record.recordId} to writer dead letter stream: $reason")
            return
        }

        logger.warn("Message writer failed for ${record.recordId}; record remains pending: $reason")
    }

    private fun writeBatch(records: List<MessageStreamRecord>, consumerGroup: String): Int {
        if (records.isEmpty()) {
            return 0
        }

        return try {
            val result = messageWritePort.write(records.map { it.envelope.toWriteRequest() })
            if (result.outcomes.size != records.size) {
                throw IllegalStateException(
                    "MessageWritePort returned ${result.outcomes.size} outcomes for ${records.size} records",
                )
            }
            records.forEach { record -> acknowledge(record, consumerGroup) }
            result.writtenCount
        } catch (e: Exception) {
            records.forEach { handleFailure(it, consumerGroup, e) }
            0
        }
    }

    private fun acknowledge(record: MessageStreamRecord, consumerGroup: String) {
        messageStreamConsumer.acknowledge(
            streamKey = record.streamKey,
            consumerGroup = consumerGroup,
            recordId = record.recordId,
        )
    }

    private fun MessageStreamEnvelope.toWriteRequest(): MessageWriteRequest {
        return MessageWriteRequest(
            messageId = messageId,
            clientMessageId = clientMessageId,
            chatRoomId = chatRoomId,
            senderId = senderId,
            messageType = messageType,
            content = content,
            sequenceNumber = sequenceNumber,
            roomSeq = roomSeq,
            streamShard = streamShard,
            writeShard = writeShard,
            fanoutShard = fanoutShard,
            createdAt = createdAt,
        )
    }
}
