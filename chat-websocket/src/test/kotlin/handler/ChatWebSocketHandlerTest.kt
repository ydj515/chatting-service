package com.chat.websocket.handler

import com.chat.core.dto.ChatRoomDto
import com.chat.core.dto.MessageDto
import com.chat.core.dto.UserDto
import com.chat.core.message.command.SendMessageCommand
import com.chat.core.service.ChatService
import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import com.chat.websocket.config.WebSocketProperties
import com.chat.websocket.service.WebSocketSessionManager
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.nio.ByteBuffer
import java.time.LocalDateTime

class ChatWebSocketHandlerTest {
    @Test
    fun `revoked connection cannot dispatch a message`() {
        val manager = mock(WebSocketSessionManager::class.java)
        val service = mock(ChatService::class.java)
        val session = mock(WebSocketSession::class.java)
        `when`(session.attributes).thenReturn(mutableMapOf<String, Any>("userId" to 7L))
        `when`(manager.rejectUnauthorizedSession(session)).thenReturn(true)
        ChatWebSocketHandler(manager, service, ObjectMapper(), WebSocketProperties())
            .handleMessage(session, TextMessage("{}"))
        verifyNoInteractions(service)
    }

    @Test
    fun `connection subscribes every page including rooms beyond the first hundred`() {
        val manager = mock(WebSocketSessionManager::class.java)
        val service = mock(ChatService::class.java)
        val session = mock(WebSocketSession::class.java)
        `when`(session.attributes).thenReturn(mutableMapOf<String, Any>("userId" to 7L))
        val rooms = (1L..101L).map { id ->
            mock(ChatRoomDto::class.java).also { `when`(it.id).thenReturn(id) }
        }
        val first = PageRequest.of(0, 100)
        `when`(service.getChatRooms(7, first)).thenReturn(PageImpl(rooms.take(100), first, 101))
        `when`(service.getChatRooms(7, first.next())).thenReturn(PageImpl(rooms.drop(100), first.next(), 101))

        ChatWebSocketHandler(manager, service, ObjectMapper(), WebSocketProperties()).afterConnectionEstablished(session)

        rooms.forEach { verify(manager).joinRoom(7, it.id) }
        verify(service).getChatRooms(7, first)
        verify(service).getChatRooms(7, first.next())
        verifyNoMoreInteractions(service)
    }

    @Test
    fun `application input rejection becomes format error without acknowledgement`() {
        val manager = mock(WebSocketSessionManager::class.java)
        val service = mock(ChatService::class.java)
        org.mockito.Mockito.doThrow(IllegalArgumentException("clientMessageId must be at most 128 characters"))
            .`when`(service).sendMessage(SendMessageCommand(10, MessageType.TEXT, null, "x".repeat(129)), 7)
        val mapper = ObjectMapper().registerModule(JavaTimeModule()).registerModule(KotlinModule.Builder().build()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val handler = ChatWebSocketHandler(manager, service, mapper, WebSocketProperties())
        val session = mock(WebSocketSession::class.java)
        `when`(session.attributes).thenReturn(mutableMapOf<String, Any>("userId" to 7L))
        handler.handleMessage(session, TextMessage("""{"type":"SEND_MESSAGE","chatRoomId":10,"messageType":"TEXT","clientMessageId":"${"x".repeat(129)}"}"""))
        val replies = mockingDetails(manager).invocations.filter { it.method.name == "sendTextToSession" }.map { it.arguments[1].toString() }
        org.junit.jupiter.api.Assertions.assertEquals(1, replies.size)
        assertTrue(replies.single().contains("INVALID_MESSAGE_FORMAT"))
        org.junit.jupiter.api.Assertions.assertFalse(replies.single().contains("MESSAGE_ACCEPTED"))
    }

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
        verify(sessionManager).rejectUnauthorizedSession(session)
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
                SendMessageCommand(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "hello",
                    clientMessageId = "client-1",
                ),
                7L,
            ),
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
        assertTrue(outboundInvocation.arguments[2] as Boolean)
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
                SendMessageCommand(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "hello",
                    clientMessageId = "client-1",
                ),
                7L,
            ),
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
        assertTrue(outboundInvocation.arguments[2] as Boolean)
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
                SendMessageCommand(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "blocked",
                    clientMessageId = "client-1",
                ),
                7L,
            ),
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
        assertTrue(outboundInvocation.arguments[2] as Boolean)
        assertTrue(payload.contains("\"type\":\"ERROR\""), payload)
        assertTrue(payload.contains("\"code\":\"MESSAGE_MODERATION_REJECTED\""), payload)
        assertTrue(payload.contains("\"message\":\"message blocked by moderation policy\""), payload)
        assertTrue(!payload.contains("\"type\":\"MESSAGE_ACCEPTED\""), payload)
    }

    private fun messageDto(): MessageDto =
        MessageDto(
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
