package com.chat.persistence.service

import com.chat.domain.model.Message
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    prefix = "chat.message.store",
    name = ["target"],
    havingValue = "legacy",
    matchIfMissing = true,
)
class JpaMessageWriteAdapter(
    private val messageRepository: MessageRepository,
    private val chatRoomRepository: ChatRoomRepository,
    private val userRepository: UserRepository,
) : MessageWritePort {

    override fun write(requests: List<MessageWriteRequest>): MessageWriteResult {
        if (requests.isEmpty()) {
            return MessageWriteResult(emptyList())
        }

        val outcomes = MutableList<MessageWriteOutcome?>(requests.size) { null }
        val pendingWrites = mutableListOf<PendingJpaWrite>()

        requests.forEachIndexed { index, request ->
            val message = messageForWrite(request)
            if (message == null) {
                outcomes[index] = MessageWriteOutcome(request = request, written = false)
            } else {
                pendingWrites += PendingJpaWrite(index = index, request = request, message = message)
            }
        }

        if (pendingWrites.isEmpty()) {
            return outcomes.toResult()
        }

        return try {
            messageRepository.saveAllAndFlush(pendingWrites.map { it.message })
            pendingWrites.forEach { pendingWrite ->
                outcomes[pendingWrite.index] = MessageWriteOutcome(
                    request = pendingWrite.request,
                    written = true,
                )
            }
            outcomes.toResult()
        } catch (e: DataIntegrityViolationException) {
            writeIndividually(pendingWrites, outcomes)
        }
    }

    private fun writeIndividually(
        pendingWrites: List<PendingJpaWrite>,
        outcomes: MutableList<MessageWriteOutcome?>,
    ): MessageWriteResult {
        pendingWrites.forEach { pendingWrite ->
            outcomes[pendingWrite.index] = MessageWriteOutcome(
                request = pendingWrite.request,
                written = writeOne(pendingWrite),
            )
        }

        return outcomes.toResult()
    }

    private fun writeOne(pendingWrite: PendingJpaWrite): Boolean {
        return try {
            messageRepository.saveAndFlush(pendingWrite.message)
            true
        } catch (e: DataIntegrityViolationException) {
            if (isDuplicate(pendingWrite.request)) {
                false
            } else {
                throw e
            }
        }
    }

    private fun messageForWrite(request: MessageWriteRequest): Message? {
        if (messageRepository.findByMessageId(request.messageId).isPresent) {
            return null
        }

        if (request.clientMessageId != null) {
            val existingMessage = messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                chatRoomId = request.chatRoomId,
                senderId = request.senderId,
                clientMessageId = request.clientMessageId,
            )
            if (existingMessage.isPresent) {
                return null
            }
        }

        return Message(
            messageId = request.messageId,
            clientMessageId = request.clientMessageId,
            chatRoom = chatRoomRepository.getReferenceById(request.chatRoomId),
            sender = userRepository.getReferenceById(request.senderId),
            type = request.messageType,
            content = request.content,
            sequenceNumber = request.sequenceNumber,
            roomSeq = request.roomSeq,
            streamShard = request.streamShard,
            writeShard = request.writeShard,
            fanoutShard = request.fanoutShard,
            createdAt = request.createdAt,
        )
    }

    private fun isDuplicate(request: MessageWriteRequest): Boolean {
        if (messageRepository.findByMessageId(request.messageId).isPresent) {
            return true
        }

        if (request.clientMessageId == null) {
            return false
        }

        return messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
            chatRoomId = request.chatRoomId,
            senderId = request.senderId,
            clientMessageId = request.clientMessageId,
        ).isPresent
    }

    private fun MutableList<MessageWriteOutcome?>.toResult(): MessageWriteResult {
        return MessageWriteResult(
            outcomes = mapIndexed { index, outcome ->
                outcome ?: error("Missing write outcome for index $index")
            },
        )
    }

    private data class PendingJpaWrite(
        val index: Int,
        val request: MessageWriteRequest,
        val message: Message,
    )
}
