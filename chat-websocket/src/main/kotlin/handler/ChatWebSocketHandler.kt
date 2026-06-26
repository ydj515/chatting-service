package com.chat.websocket.handler

import com.chat.domain.dto.ErrorMessage
import com.chat.domain.dto.MessageAccepted
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import com.chat.domain.service.ChatService
import com.chat.persistence.service.WebSocketSessionManager
import com.chat.websocket.config.WebSocketProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession

@Component
class ChatWebSocketHandler(
    private val sessionManager: WebSocketSessionManager,
    private val chatService: ChatService,
    private val objectMapper: ObjectMapper,
    private val webSocketProperties: WebSocketProperties,
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val userId = getUserIdFromSession(session)

        if (userId != null) {
            sessionManager.addSession(userId, session)
            logger.info("Session $userId established for $userId")

            try {
                loadUserChatRooms(userId)
            } catch (e: Exception) {
                logger.error("Error while loading user chat rooms", e)
            }
        }
    }

    override fun handleMessage(
        session: WebSocketSession,
        message: WebSocketMessage<*>,
    ) {
        val userId = getUserIdFromSession(session) ?: return

        try {
            when (message) {
                is TextMessage -> {
                    handleTextMessage(session, userId, message.payload)
                }

                else -> {
                    logger.warn("Unsupported message type ${message.javaClass.name}")
                }
            }
        } catch (e: Exception) {
            logger.warn("exception while processing message", e)
            sendErrorMessage(session, "메시지 처리 에러")
        }
    }

    override fun handleTransportError(
        session: WebSocketSession,
        exception: Throwable,
    ) {
        val userId = getUserIdFromSession(session)

        // EOFException -> 클라이언트 연결 해제, 정상적인 상황 (로그레벨을 따로 두기 위해)
        if (exception is java.io.EOFException) {
            logger.debug("WebSocket connection closed by client for user: $userId")
        } else {
            logger.error("WebSocket transport error for user: $userId", exception)
        }

        if (userId != null) {
            sessionManager.removeSession(userId, session)
        }
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        closeStatus: CloseStatus,
    ) {
        val userId = getUserIdFromSession(session)
        if (userId != null) {
            sessionManager.removeSession(userId, session)
            logger.info("Session removed for $userId")
        }
    }

    override fun supportsPartialMessages(): Boolean = false

    private fun getUserIdFromSession(session: WebSocketSession): Long? {
        return session.attributes[webSocketProperties.userIdAttribute] as? Long
    }

    private fun loadUserChatRooms(userId: Long) {
        try {
            val chatRooms = chatService.getChatRooms(
                userId,
                PageRequest.of(0, webSocketProperties.initialChatRoomPageSize),
            )

            chatRooms.content.forEach { room ->
                sessionManager.joinRoom(userId, room.id)
            }

            logger.info("Loaded ${chatRooms.content.size} chat rooms for user: $userId")

        } catch (e: Exception) {
            logger.error("Failed to load chat rooms for user: $userId", e)
        }
    }

    private fun sendErrorMessage(session: WebSocketSession, errorMessage: String, errorCode: String? = null) {
        try {
            val error = ErrorMessage(
                chatRoomId = null,
                message = errorMessage,
                code = errorCode,
            )
            val json = writeWebSocketMessage(error)
            if (!sessionManager.sendTextToSession(session, json)) {
                logger.warn("Failed to enqueue error message for session ${session.id}")
            }
        } catch (e: Exception) {
            logger.error("Failed to send error message", e)
        }
    }

    private fun extractMessageType(payload: String): String? {
        return try {
            objectMapper.readTree(payload).get("type")?.asText()
        } catch (e: Exception) {
            null
        }
    }

    private fun handleTextMessage(session: WebSocketSession, userId: Long, payload: String) {
        try {
            val messageType = extractMessageType(payload)

            when (messageType) {
                ClientMessageType.SEND_MESSAGE.name -> handleSendMessage(session, userId, payload)

                else -> {
                    logger.warn("Unknown message type: $messageType")
                    sendErrorMessage(session, "알 수 없는 메시지 타입입니다: $messageType", WebSocketErrorCode.UNKNOWN_MESSAGE_TYPE.name)
                }
            }
        } catch (e: Exception) {
            logger.error("Error parsing WebSocket message from user $userId: ${e.message}", e)
            sendErrorMessage(session, "메시지 형식이 올바르지 않습니다.", WebSocketErrorCode.INVALID_MESSAGE_FORMAT.name)
        }
    }

    private fun handleSendMessage(session: WebSocketSession, userId: Long, payload: String) {
        val request = objectMapper.readValue(payload, ClientSendMessageRequest::class.java)
        val chatRoomId = request.chatRoomId ?: throw IllegalArgumentException("chatRoomId is required")
        val messageType = request.messageType ?: throw IllegalArgumentException("messageType is required")

        val savedMessage = try {
            chatService.sendMessage(
                SendMessageRequest(
                    chatRoomId = chatRoomId,
                    type = messageType,
                    content = request.content,
                    clientMessageId = request.clientMessageId,
                ),
                userId,
            )
        } catch (e: MessageAdmissionRejectedException) {
            sendErrorMessage(
                session = session,
                errorMessage = e.message ?: "메시지 전송 제한을 초과했습니다.",
                errorCode = WebSocketErrorCode.MESSAGE_ADMISSION_REJECTED.name,
            )
            return
        } catch (e: MessageModerationRejectedException) {
            sendErrorMessage(
                session = session,
                errorMessage = e.message ?: "메시지가 moderation 정책에 의해 거부되었습니다.",
                errorCode = WebSocketErrorCode.MESSAGE_MODERATION_REJECTED.name,
            )
            return
        }
        sendAcceptedMessage(session, savedMessage)
    }

    private fun sendAcceptedMessage(session: WebSocketSession, message: com.chat.domain.dto.MessageDto) {
        try {
            val accepted = MessageAccepted(
                id = message.id,
                messageId = message.messageId,
                clientMessageId = message.clientMessageId,
                roomId = message.chatRoomId,
                roomSeq = message.roomSeq,
                sequenceNumber = message.sequenceNumber,
                chatRoomId = message.chatRoomId,
                timestamp = message.createdAt,
            )
            val json = writeWebSocketMessage(accepted)
            if (!sessionManager.sendTextToSession(session, json)) {
                logger.warn("Failed to enqueue accepted message for session ${session.id}")
            }
        } catch (e: Exception) {
            logger.error("Failed to send accepted message", e)
        }
    }

    private data class ClientSendMessageRequest(
        val chatRoomId: Long?,
        val messageType: MessageType?,
        val content: String?,
        val clientMessageId: String?,
    )

    private enum class ClientMessageType {
        SEND_MESSAGE,
    }

    private enum class WebSocketErrorCode {
        UNKNOWN_MESSAGE_TYPE,
        INVALID_MESSAGE_FORMAT,
        MESSAGE_ADMISSION_REJECTED,
        MESSAGE_MODERATION_REJECTED,
    }

    private fun writeWebSocketMessage(message: com.chat.domain.dto.WebSocketMessage): String {
        return objectMapper.writerFor(com.chat.domain.dto.WebSocketMessage::class.java).writeValueAsString(message)
    }
}
