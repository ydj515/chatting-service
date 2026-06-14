package com.chat.persistence.service

import com.chat.domain.model.MessageType
import java.time.LocalDateTime

interface MessageWritePort {
    fun write(requests: List<MessageWriteRequest>): MessageWriteResult
}

data class MessageWriteRequest(
    val messageId: String,
    val clientMessageId: String?,
    val chatRoomId: Long,
    val senderId: Long,
    val messageType: MessageType,
    val content: String?,
    val sequenceNumber: Long,
    val roomSeq: Long,
    val streamShard: Int,
    val writeShard: Int,
    val fanoutShard: Int,
    val createdAt: LocalDateTime,
)

data class MessageWriteResult(
    val outcomes: List<MessageWriteOutcome>,
) {
    val writtenCount: Int
        get() = outcomes.count { it.written }
}

data class MessageWriteOutcome(
    val request: MessageWriteRequest,
    val written: Boolean,
)
