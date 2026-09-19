package com.chat.persistence.service

import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.Message
import com.chat.domain.model.User
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamProducer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@Service
class MessageStreamPublisher(
    private val messageSequenceService: MessageSequenceService,
    private val roomStorageConfigReader: RoomStorageConfigReader,
    private val messageStreamProducer: MessageStreamProducer,
    private val roomTrafficStatsService: RoomTrafficStatsService,
) {
    private val logger = LoggerFactory.getLogger(MessageStreamPublisher::class.java)
    private val secureRandom = SecureRandom()

    fun findAccepted(roomId: Long, sender: User, clientMessageId: String): MessageDto? =
        messageStreamProducer.findAccepted(roomId, sender.id, clientMessageId)?.toDto(sender)

    fun publish(request: SendMessageRequest, chatRoom: ChatRoom, sender: User): MessageDto {
        val messageId = generateMessageId()
        val clientMessageId = request.clientMessageId?.trim()?.takeIf { it.isNotEmpty() } ?: "server:$messageId"
        val roomSeq = messageSequenceService.getNextSequence(request.chatRoomId)
        val shardConfig = roomStorageConfigReader.shardConfig(request.chatRoomId)
        val streamShard = streamShard(roomSeq, shardConfig.fanoutShardCount)

        val message = Message(
            messageId = messageId,
            clientMessageId = clientMessageId,
            content = request.content,
            type = request.type,
            chatRoom = chatRoom,
            sender = sender,
            sequenceNumber = roomSeq,
            roomSeq = roomSeq,
            streamShard = streamShard,
            writeShard = writeShard(messageId, shardConfig.writeShardCount),
            fanoutShard = fanoutShard(streamShard),
        )

        val accepted = messageStreamProducer.append(messageToStreamEnvelope(message))
        if (accepted.messageId == messageId) recordAcceptedBestEffort(request.chatRoomId)

        return accepted.toDto(sender)
    }

    private fun MessageStreamEnvelope.toDto(user: User): MessageDto =
        MessageDto(
            id = 0,
            chatRoomId = chatRoomId,
            sender = user.toUserDto(),
            isEdited = false,
            isDeleted = false,
            editedAt = null,
            messageId = messageId,
            clientMessageId = clientMessageId,
            type = messageType,
            content = content,
            createdAt = createdAt,
            sequenceNumber = sequenceNumber,
            roomSeq = roomSeq,
            streamShard = streamShard,
            writeShard = writeShard,
            fanoutShard = fanoutShard,
        )

    private fun recordAcceptedBestEffort(roomId: Long) {
        try {
            roomTrafficStatsService.recordAccepted(roomId)
        } catch (e: RuntimeException) {
            logger.warn("Failed to record accepted room traffic stats for room {}", roomId, e)
        }
    }

    private fun messageToStreamEnvelope(message: Message): MessageStreamEnvelope {
        val roomSeq = if (message.roomSeq > 0) message.roomSeq else message.sequenceNumber
        return MessageStreamEnvelope(
            messageId = message.messageId ?: legacyMessageId(message.id),
            clientMessageId = message.clientMessageId,
            chatRoomId = message.chatRoom.id,
            senderId = message.sender.id,
            senderName = message.sender.displayName,
            messageType = message.type,
            content = message.content,
            sequenceNumber = message.sequenceNumber,
            roomSeq = roomSeq,
            streamShard = message.streamShard,
            writeShard = message.writeShard,
            fanoutShard = message.fanoutShard,
            createdAt = message.createdAt,
        )
    }

    private fun generateMessageId(): String {
        val timestamp = Instant.now().toEpochMilli().toString(36).padStart(9, '0')
        val randomBytes = ByteArray(10)
        secureRandom.nextBytes(randomBytes)
        val randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        return "msg_${timestamp}_$randomPart"
    }

    private fun legacyMessageId(id: Long): String = "legacy:$id"

    private fun streamShard(roomSeq: Long, shardCount: Int): Int = Math.floorMod(roomSeq - 1, shardCount.coerceAtLeast(1).toLong()).toInt()

    private fun writeShard(messageId: String, shardCount: Int): Int = shard(messageId, shardCount)

    private fun fanoutShard(streamShard: Int): Int = streamShard

    private fun shard(value: String, shardCount: Int): Int = Math.floorMod(value.hashCode(), shardCount.coerceAtLeast(1))
}
