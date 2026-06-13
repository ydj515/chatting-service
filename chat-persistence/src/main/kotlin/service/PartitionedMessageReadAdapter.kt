package com.chat.persistence.service

import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.UserDto
import com.chat.persistence.repository.CanonicalMessageRecord
import com.chat.persistence.repository.PartitionedMessageReadRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    prefix = "chat.message.store",
    name = ["target"],
    havingValue = "partitioned",
)
class PartitionedMessageReadAdapter(
    private val partitionedMessageReadRepository: PartitionedMessageReadRepository,
) : MessageReadPort {

    override fun findPageByRoom(roomId: Long, pageable: Pageable): Page<MessageDto> {
        return partitionedMessageReadRepository.findPageByRoom(roomId, pageable)
            .map { it.toDto() }
    }

    override fun findLatestMessages(roomId: Long, limit: Int): List<MessageDto> {
        return partitionedMessageReadRepository.findLatestMessages(roomId, limit)
            .map { it.toDto() }
    }

    override fun findMessagesBefore(roomId: Long, cursor: Long, limit: Int): List<MessageDto> {
        return partitionedMessageReadRepository.findMessagesBefore(roomId, cursor, limit)
            .map { it.toDto() }
    }

    override fun findMessagesAfter(roomId: Long, cursor: Long, limit: Int): List<MessageDto> {
        return partitionedMessageReadRepository.findMessagesAfter(roomId, cursor, limit)
            .map { it.toDto() }
    }

    override fun findGapMessages(roomId: Long, afterSeq: Long, limit: Int): List<MessageDto> {
        return partitionedMessageReadRepository.findGapMessages(roomId, afterSeq, limit)
            .map { it.toDto() }
    }

    override fun findLatestMessage(roomId: Long): MessageDto? {
        return partitionedMessageReadRepository.findLatestMessage(roomId)?.toDto()
    }

    private fun CanonicalMessageRecord.toDto(): MessageDto {
        return MessageDto(
            id = roomSeq,
            messageId = messageId,
            clientMessageId = clientMessageId,
            chatRoomId = roomId,
            sender = UserDto(
                id = senderId,
                username = senderUsername,
                displayName = senderDisplayName,
                profileImageUrl = senderProfileImageUrl,
                status = senderStatus,
                isActive = senderIsActive,
                lastSeenAt = senderLastSeenAt,
                createdAt = senderCreatedAt,
            ),
            type = messageType,
            content = content,
            isEdited = false,
            isDeleted = isDeleted,
            createdAt = createdAt,
            editedAt = null,
            sequenceNumber = roomSeq,
            roomSeq = roomSeq,
            streamShard = 0,
            writeShard = writeShard,
            fanoutShard = 0,
        )
    }
}
