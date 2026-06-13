package com.chat.persistence.service

import com.chat.domain.model.Message
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class MessageWriterWorker(
    private val messageStreamConsumer: MessageStreamConsumer,
    private val messageRepository: MessageRepository,
    private val chatRoomRepository: ChatRoomRepository,
    private val userRepository: UserRepository,
    private val workerProperties: ChatWorkerProperties,
) {

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
        )

        records.forEach { record ->
            if (write(record.envelope)) {
                written += 1
            }
            messageStreamConsumer.acknowledge(
                streamKey = record.streamKey,
                consumerGroup = consumerGroup,
                recordId = record.recordId,
            )
        }

        return written
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
