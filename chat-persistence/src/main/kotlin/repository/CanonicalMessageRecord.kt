package com.chat.persistence.repository

import com.chat.domain.model.MessageType
import java.time.LocalDateTime

data class CanonicalMessageRecord(
    val messageId: String,
    val clientMessageId: String?,
    val roomId: Long,
    val roomSeq: Long,
    val writeShard: Int,
    val senderId: Long,
    val senderUsername: String,
    val senderDisplayName: String,
    val senderProfileImageUrl: String?,
    val senderStatus: String?,
    val senderIsActive: Boolean,
    val senderLastSeenAt: LocalDateTime?,
    val senderCreatedAt: LocalDateTime,
    val messageType: MessageType,
    val content: String?,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime,
)
