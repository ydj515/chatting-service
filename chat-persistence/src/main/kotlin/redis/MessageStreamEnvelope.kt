package com.chat.persistence.redis

import com.chat.domain.model.MessageType
import java.time.LocalDateTime

data class MessageStreamEnvelope(
    val messageId: String,
    val clientMessageId: String?,
    val chatRoomId: Long,
    val senderId: Long,
    val senderName: String,
    val messageType: MessageType,
    val content: String?,
    val sequenceNumber: Long,
    val roomSeq: Long,
    val streamShard: Int,
    val writeShard: Int,
    val fanoutShard: Int,
    val createdAt: LocalDateTime,
)
