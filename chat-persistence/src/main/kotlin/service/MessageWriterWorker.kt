package com.chat.persistence.service

import com.chat.domain.model.Message
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessageWriterWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val messageRepository: MessageRepository,
    private val chatRoomRepository: ChatRoomRepository,
    private val userRepository: UserRepository,
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
        val pendingWrites = mutableListOf<PendingWrite>()

        records.forEach { record ->
            try {
                val message = messageForWrite(record.envelope)
                if (message == null) {
                    acknowledge(record, consumerGroup)
                } else {
                    pendingWrites += PendingWrite(record, message)
                }
            } catch (e: Exception) {
                handleFailure(record, consumerGroup, e)
            }
        }

        if (pendingWrites.isEmpty()) {
            return 0
        }

        return try {
            messageRepository.saveAllAndFlush(pendingWrites.map { it.message })
            pendingWrites.forEach { acknowledge(it.record, consumerGroup) }
            pendingWrites.size
        } catch (e: DataIntegrityViolationException) {
            var written = 0
            pendingWrites.forEach { pendingWrite ->
                if (processRecord(pendingWrite.record, consumerGroup) { writeOne(pendingWrite.record.envelope) }) {
                    written += 1
                }
            }
            written
        } catch (e: Exception) {
            pendingWrites.forEach { handleFailure(it.record, consumerGroup, e) }
            0
        }
    }

    private fun processRecord(
        record: MessageStreamRecord,
        consumerGroup: String,
        handler: () -> Boolean,
    ): Boolean {
        return try {
            val written = handler()
            acknowledge(record, consumerGroup)
            written
        } catch (e: Exception) {
            handleFailure(record, consumerGroup, e)
            false
        }
    }

    private fun acknowledge(record: MessageStreamRecord, consumerGroup: String) {
        messageStreamConsumer.acknowledge(
            streamKey = record.streamKey,
            consumerGroup = consumerGroup,
            recordId = record.recordId,
        )
    }

    private fun writeOne(envelope: MessageStreamEnvelope): Boolean {
        val message = messageForWrite(envelope) ?: return false
        return try {
            messageRepository.saveAndFlush(message)
            true
        } catch (e: DataIntegrityViolationException) {
            if (isDuplicate(envelope)) {
                false
            } else {
                throw e
            }
        }
    }

    private fun messageForWrite(envelope: MessageStreamEnvelope): Message? {
        if (messageRepository.findByMessageId(envelope.messageId).isPresent) {
            return null
        }

        if (envelope.clientMessageId != null) {
            val existingMessage = messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                chatRoomId = envelope.chatRoomId,
                senderId = envelope.senderId,
                clientMessageId = envelope.clientMessageId,
            )
            if (existingMessage.isPresent) {
                return null
            }
        }

        return Message(
            messageId = envelope.messageId,
            clientMessageId = envelope.clientMessageId,
            chatRoom = chatRoomRepository.getReferenceById(envelope.chatRoomId),
            sender = userRepository.getReferenceById(envelope.senderId),
            type = envelope.messageType,
            content = envelope.content,
            sequenceNumber = envelope.sequenceNumber,
            roomSeq = envelope.roomSeq,
            streamShard = envelope.streamShard,
            writeShard = envelope.writeShard,
            fanoutShard = envelope.fanoutShard,
            createdAt = envelope.createdAt,
        )
    }

    private fun isDuplicate(envelope: MessageStreamEnvelope): Boolean {
        if (messageRepository.findByMessageId(envelope.messageId).isPresent) {
            return true
        }

        if (envelope.clientMessageId == null) {
            return false
        }

        return messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
            chatRoomId = envelope.chatRoomId,
            senderId = envelope.senderId,
            clientMessageId = envelope.clientMessageId,
        ).isPresent
    }

    private data class PendingWrite(
        val record: MessageStreamRecord,
        val message: Message,
    )
}
