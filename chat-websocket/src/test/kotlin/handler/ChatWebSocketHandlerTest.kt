package com.chat.websocket.handler

import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.dto.UserDto
import com.chat.domain.model.MessageType
import com.chat.domain.service.ChatService
import com.chat.persistence.service.WebSocketSessionManager
import com.chat.websocket.config.WebSocketProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.LocalDateTime

class ChatWebSocketHandlerTest {

    @Test
    fun `SEND_MESSAGE ACK는 raw session이 아니라 session manager outbound 경로로 전송한다`() {
        val sessionManager = mock(WebSocketSessionManager::class.java)
        val chatService = mock(ChatService::class.java)
        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val handler = ChatWebSocketHandler(
            sessionManager = sessionManager,
            chatService = chatService,
            objectMapper = objectMapper,
            webSocketProperties = WebSocketProperties(userIdAttribute = "userId"),
        )
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn("session-1")
        `when`(session.attributes).thenReturn(mutableMapOf<String, Any>("userId" to 7L))
        `when`(
            chatService.sendMessage(
                SendMessageRequest(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "hello",
                    clientMessageId = "client-1",
                ),
                7L,
            )
        ).thenReturn(messageDto())

        handler.handleMessage(
            session,
            TextMessage(
                """
                {
                  "type": "SEND_MESSAGE",
                  "chatRoomId": 10,
                  "messageType": "TEXT",
                  "content": "hello",
                  "clientMessageId": "client-1"
                }
                """.trimIndent(),
            ),
        )

        val outboundInvocation = mockingDetails(sessionManager).invocations
            .single { it.method.name == "sendTextToSession" }
        val payload = outboundInvocation.arguments[1] as String
        assertSame(session, outboundInvocation.arguments[0])
        assertTrue(payload.contains("\"type\":\"MESSAGE_ACCEPTED\""), payload)
        assertTrue(mockingDetails(session).invocations.none { it.method.name == "sendMessage" })
    }

    private fun messageDto(): MessageDto {
        return MessageDto(
            id = 100L,
            messageId = "msg-100",
            clientMessageId = "client-1",
            chatRoomId = 10L,
            sender = UserDto(
                id = 7L,
                username = "sender",
                displayName = "Sender",
                profileImageUrl = null,
                status = null,
                isActive = true,
                lastSeenAt = null,
                createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            ),
            type = MessageType.TEXT,
            content = "hello",
            isEdited = false,
            isDeleted = false,
            createdAt = LocalDateTime.parse("2026-06-12T12:00:01"),
            editedAt = null,
            sequenceNumber = 1L,
            roomSeq = 1L,
            streamShard = 0,
            writeShard = 0,
            fanoutShard = 0,
        )
    }
}
