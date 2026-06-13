package com.chat.persistence.service

import com.chat.domain.model.Message
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
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

    fun pollAndWrite(): Int {
        val streamKeys = messageStreamConsumer.listStreamKeys()
        if (streamKeys.isEmpty()) {
            return 0
        }

        val consumerGroup = workerProperties.writer.consumerGroup
        streamKeys.forEach { streamKey ->
            messageStreamConsumer.ensureConsumerGroup(streamKey, consumerGroup)
        }

        var written = 0
        val records = messageStreamConsumer.readNew(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = streamKeys,
            count = workerProperties.writer.readCount,
        ) + messageStreamConsumer.claimPending(
            consumerGroup = consumerGroup,
            consumerName = workerProperties.consumerName,
            streamKeys = streamKeys,
            count = workerProperties.writer.readCount,
            minIdleMillis = workerProperties.writer.minIdleMillis,
        )

        records.forEach { record ->
            if (processRecord(record, consumerGroup) { write(record.envelope) }) {
                written += 1
            }
        }

        return written
    }

    private fun processRecord(
        record: com.chat.persistence.redis.MessageStreamRecord,
        consumerGroup: String,
        handler: () -> Boolean,
    ): Boolean {
        return try {
            val written = handler()
            messageStreamConsumer.acknowledge(
                streamKey = record.streamKey,
                consumerGroup = consumerGroup,
                recordId = record.recordId,
            )
            written
        } catch (e: Exception) {
            handleFailure(record, consumerGroup, e)
            false
        }
    }

    private fun handleFailure(
        record: com.chat.persistence.redis.MessageStreamRecord,
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

    private fun write(envelope: MessageStreamEnvelope): Boolean {
        if (messageRepository.findByMessageId(envelope.messageId).isPresent) {
            return false
        }

        if (envelope.clientMessageId != null) {
            val existingMessage = messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                chatRoomId = envelope.chatRoomId,
                senderId = envelope.senderId,
                clientMessageId = envelope.clientMessageId,
            )
            if (existingMessage.isPresent) {
                return false
            }
        }

        val message = Message(
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
}
