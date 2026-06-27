package com.chat.websocket.handler

import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.exception.MessageModerationRejectedException
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
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.nio.ByteBuffer
import java.time.LocalDateTime

class ChatWebSocketHandlerTest {

    @Test
    fun `PONG frame은 세션 activity로 기록하고 비즈니스 메시지로 처리하지 않는다`() {
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

        handler.handleMessage(session, PongMessage(ByteBuffer.allocate(0)))

        verify(sessionManager).recordSessionActivity(session)
        verifyNoMoreInteractions(sessionManager)
        verifyNoInteractions(chatService)
    }

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

    @Test
    fun `메시지 수락 정책 거부는 MESSAGE_ACCEPTED 없이 전송 제한 에러를 outbound 경로로 전송한다`() {
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
        ).thenThrow(MessageAdmissionRejectedException("slow mode active"))

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
        assertTrue(payload.contains("\"type\":\"ERROR\""), payload)
        assertTrue(payload.contains("\"code\":\"MESSAGE_ADMISSION_REJECTED\""), payload)
        assertTrue(payload.contains("\"message\":\"slow mode active\""), payload)
        assertTrue(!payload.contains("\"type\":\"MESSAGE_ACCEPTED\""), payload)
    }

    @Test
    fun `moderation 거부는 MESSAGE_ACCEPTED 없이 moderation 에러를 outbound 경로로 전송한다`() {
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
                    content = "blocked",
                    clientMessageId = "client-1",
                ),
                7L,
            )
        ).thenThrow(MessageModerationRejectedException("message blocked by moderation policy"))

        handler.handleMessage(
            session,
            TextMessage(
                """
                {
                  "type": "SEND_MESSAGE",
                  "chatRoomId": 10,
                  "messageType": "TEXT",
                  "content": "blocked",
                  "clientMessageId": "client-1"
                }
                """.trimIndent(),
            ),
        )

        val outboundInvocation = mockingDetails(sessionManager).invocations
            .single { it.method.name == "sendTextToSession" }
        val payload = outboundInvocation.arguments[1] as String
        assertSame(session, outboundInvocation.arguments[0])
        assertTrue(payload.contains("\"type\":\"ERROR\""), payload)
        assertTrue(payload.contains("\"code\":\"MESSAGE_MODERATION_REJECTED\""), payload)
        assertTrue(payload.contains("\"message\":\"message blocked by moderation policy\""), payload)
        assertTrue(!payload.contains("\"type\":\"MESSAGE_ACCEPTED\""), payload)
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
