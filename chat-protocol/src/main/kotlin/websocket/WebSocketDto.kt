package com.chat.protocol.websocket

import com.chat.domain.model.MessageType
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.LocalDateTime

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ChatMessage::class, name = "CHAT_MESSAGE"),
    JsonSubTypes.Type(value = ChatMessageBatch::class, name = "CHAT_MESSAGE_BATCH"),
    JsonSubTypes.Type(value = MessageAccepted::class, name = "MESSAGE_ACCEPTED"),
    JsonSubTypes.Type(value = ErrorMessage::class, name = "ERROR"),
)
sealed class WebSocketMessage {
    abstract val chatRoomId: Long?
    abstract val timestamp: LocalDateTime
}

// 서버 -> 클라이언트 메시지들
data class ChatMessage(
    val id: Long,
    val messageId: String,
    val clientMessageId: String?,
    val content: String,
    val messageType: MessageType,
    val senderId: Long,
    val senderName: String,
    val sequenceNumber: Long,
    val roomSeq: Long,
    val streamShard: Int,
    val writeShard: Int,
    val fanoutShard: Int,
    override val chatRoomId: Long,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
) : WebSocketMessage()

data class ChatMessageBatch(
    val messages: List<ChatMessage>,
    override val chatRoomId: Long?,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
) : WebSocketMessage()

data class MessageAccepted(
    val id: Long,
    val messageId: String,
    val clientMessageId: String?,
    val roomId: Long,
    val roomSeq: Long,
    val sequenceNumber: Long,
    override val chatRoomId: Long,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
) : WebSocketMessage()

data class ErrorMessage(
    val message: String,
    val code: String? = null,
    override val chatRoomId: Long?,
    override val timestamp: LocalDateTime = LocalDateTime.now(),
) : WebSocketMessage()
