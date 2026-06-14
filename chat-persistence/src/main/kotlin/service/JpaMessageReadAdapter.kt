package com.chat.persistence.service

import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.UserDto
import com.chat.domain.model.Message
import com.chat.persistence.repository.MessageRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    prefix = "chat.message.store",
    name = ["target"],
    havingValue = "legacy",
    matchIfMissing = true,
)
class JpaMessageReadAdapter(
    private val messageRepository: MessageRepository,
) : MessageReadPort {

    override fun findPageByRoom(roomId: Long, pageable: Pageable): Page<MessageDto> {
        return messageRepository.findByChatRoomId(roomId, pageable)
            .map { it.toDto() }
    }

    override fun findLatestMessages(roomId: Long, limit: Int): List<MessageDto> {
        return messageRepository.findLatestMessages(roomId, PageRequest.of(0, limit))
            .map { it.toDto() }
    }

    override fun findMessagesBefore(roomId: Long, cursor: Long, limit: Int): List<MessageDto> {
        return messageRepository.findMessagesBefore(roomId, cursor, PageRequest.of(0, limit))
            .map { it.toDto() }
    }

    override fun findMessagesAfter(roomId: Long, cursor: Long, limit: Int): List<MessageDto> {
        return messageRepository.findMessagesAfter(roomId, cursor, PageRequest.of(0, limit))
            .map { it.toDto() }
    }

    override fun findGapMessages(roomId: Long, afterSeq: Long, limit: Int): List<MessageDto> {
        return messageRepository.findMessagesAfter(roomId, afterSeq, PageRequest.of(0, limit))
            .map { it.toDto() }
    }

    override fun findLatestMessage(roomId: Long): MessageDto? {
        return messageRepository.findLatestMessage(roomId)?.toDto()
    }

    override fun findByClientMessageId(roomId: Long, senderId: Long, clientMessageId: String): MessageDto? {
        return messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
            chatRoomId = roomId,
            senderId = senderId,
            clientMessageId = clientMessageId,
        ).orElse(null)?.toDto()
    }

    private fun Message.toDto(): MessageDto {
        val roomSeq = if (roomSeq > 0) roomSeq else sequenceNumber
        return MessageDto(
            id = id,
            messageId = messageId ?: legacyMessageId(id),
            clientMessageId = clientMessageId,
            chatRoomId = chatRoom.id,
            sender = UserDto(
                id = sender.id,
                username = sender.username,
                displayName = sender.displayName,
                profileImageUrl = sender.profileImageUrl,
                status = sender.status,
                isActive = sender.isActive,
                lastSeenAt = sender.lastSeenAt,
                createdAt = sender.createdAt,
            ),
            type = type,
            content = content,
            isEdited = isEdited,
            isDeleted = isDeleted,
            createdAt = createdAt,
            editedAt = editedAt,
            sequenceNumber = sequenceNumber,
            roomSeq = roomSeq,
            streamShard = streamShard,
            writeShard = writeShard,
            fanoutShard = fanoutShard,
        )
    }

    private fun legacyMessageId(id: Long): String = "legacy:$id"
}
