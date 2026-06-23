package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessageWriterWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val messageWritePort: MessageWritePort,
    private val workerProperties: ChatWorkerProperties,
    private val messageStreamMetrics: MessageStreamMetrics = MessageStreamMetrics.Noop,
) {
    private val logger = LoggerFactory.getLogger(MessageWriterWorker::class.java)
    private var lastPendingClaimAtMillis = 0L

    @PostConstruct
    fun initialize() {
        logger.info("Message writer initialized with write port ${messageWritePort.javaClass.name}")
    }

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
            messageStreamMetrics.recordDeadLetter(consumerGroup, record.envelope.streamShard)
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

        val startedAtNanos = System.nanoTime()
        return try {
            val result = messageWritePort.write(records.map { it.envelope.toWriteRequest() })
            if (result.outcomes.size != records.size) {
                throw IllegalStateException(
                    "MessageWritePort returned ${result.outcomes.size} outcomes for ${records.size} records",
                )
            }
            if (result.writtenCount == 0) {
                logger.warn(
                    "Message writer processed ${records.size} records with no inserts using ${messageWritePort.javaClass.name}",
                )
            }
            records.forEach { record -> acknowledge(record, consumerGroup) }
            messageStreamMetrics.recordWorkerBatch(
                workerRole = ROLE_MESSAGE_WRITER,
                outcome = OUTCOME_SUCCESS,
                recordCount = records.size,
                durationNanos = System.nanoTime() - startedAtNanos,
            )
            result.writtenCount
        } catch (e: Exception) {
            logger.warn(
                "Message writer batch failed for ${records.size} records; retrying records individually",
                e,
            )
            val writtenCount = writeIndividually(records, consumerGroup, e)
            messageStreamMetrics.recordWorkerBatch(
                workerRole = ROLE_MESSAGE_WRITER,
                outcome = if (writtenCount > 0) OUTCOME_PARTIAL else OUTCOME_FAILURE,
                recordCount = records.size,
                durationNanos = System.nanoTime() - startedAtNanos,
            )
            writtenCount
        }
    }

    private fun writeIndividually(
        records: List<MessageStreamRecord>,
        consumerGroup: String,
        batchFailure: Exception,
    ): Int {
        var writtenCount = 0
        records.forEach { record ->
            try {
                val result = messageWritePort.write(listOf(record.envelope.toWriteRequest()))
                if (result.outcomes.size != 1) {
                    throw IllegalStateException(
                        "MessageWritePort returned ${result.outcomes.size} outcomes for 1 record",
                    )
                }
                acknowledge(record, consumerGroup)
                if (result.outcomes.single().written) {
                    writtenCount += 1
                }
            } catch (e: Exception) {
                handleFailure(record, consumerGroup, e)
            }
        }
        if (writtenCount == 0) {
            logger.warn("Message writer individual retry wrote no records after batch failure: ${batchFailure.message}")
        }
        return writtenCount
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

    private companion object {
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_PARTIAL = "partial"
        const val OUTCOME_SUCCESS = "success"
        const val ROLE_MESSAGE_WRITER = "message-writer"
    }
}
