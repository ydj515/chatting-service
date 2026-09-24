package com.chat.application.gateway

import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.redis.RedisGatewayRoomTransport
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.GatewayMembershipsAdapter
import com.chat.protocol.websocket.ChatMessage
import com.chat.websocket.config.ChatWebSocketGatewayProperties
import com.chat.websocket.service.WebSocketRoomSubscriptions
import com.chat.websocket.service.WebSocketSessionAuthorization
import com.chat.websocket.service.WebSocketSessionManager
import com.chat.websocket.service.WebSocketSessionTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.LocalDateTime

class WebSocketSessionManagerTest {
    @Test
    fun `closing the last session never bulk deletes new room registrations`() {
        val members = mock(ChatRoomMemberRepository::class.java)
        `when`(members.existsByChatRoomIdAndUserIdAndIsActiveTrue(10, 7)).thenReturn(true)
        val fixture = sessionManagerFixture(members)
        val previous = session("old")
        val current = session("new")
        fixture.manager.addSession(7, previous)
        fixture.manager.joinRoom(7, 10)
        fixture.manager.removeSession(7, previous)
        fixture.manager.addSession(7, current)
        fixture.manager.joinRoom(7, 10)
        verify(fixture.redisTemplate, never()).delete(anyString())
    }

    @Test
    fun `원격 JOIN membership event는 열린 local session을 방 인덱스에 추가한다`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        `when`(chatRoomMemberRepository.findActiveUserIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList<Long>())).thenAnswer { it.getArgument<List<Long>>(1) }
        `when`(chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L)).thenReturn(true)
        val session = session("session-local")
        val fixture = sessionManagerFixture(chatRoomMemberRepository)
        fixture.manager.initialize()
        fixture.manager.addSession(7L, session)

        val event = RedisMessageBroker.DistributedMembershipEvent(
            id = "remote-join",
            serverId = "api-server",
            userId = 7L,
            roomId = 10L,
            action = RedisMessageBroker.MembershipAction.JOIN,
            timestamp = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        fixture.redisMessageBroker.onMessage(
            DefaultMessage(
                "chat.membership".toByteArray(),
                fixture.objectMapper.writeValueAsBytes(event),
            ),
            null,
        )

        fixture.manager.sendMessageToLocalRoom(
            roomId = 10L,
            message = ChatMessage(
                id = 100L,
                messageId = "msg-100",
                clientMessageId = "client-100",
                content = "hello",
                messageType = MessageType.TEXT,
                senderId = 1L,
                senderName = "sender",
                sequenceNumber = 1L,
                roomSeq = 1L,
                streamShard = 0,
                writeShard = 0,
                fanoutShard = 0,
                chatRoomId = 10L,
                timestamp = LocalDateTime.parse("2026-06-12T12:00:01"),
            ),
        )

        verify(session).sendMessage(any(TextMessage::class.java))
    }

    @Test
    fun `room fan-out authorizes only local recipients in one batch`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        `when`(chatRoomMemberRepository.findActiveUserIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList<Long>())).thenAnswer { it.getArgument<List<Long>>(1) }
        `when`(chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 1L)).thenReturn(true)
        `when`(chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(20L, 2L)).thenReturn(true)
        val sessionInRoom = session("session-in-room")
        val sessionOutsideRoom = session("session-outside-room")
        val manager = sessionManager(chatRoomMemberRepository)

        manager.addSession(1L, sessionInRoom)
        manager.addSession(2L, sessionOutsideRoom)
        manager.joinRoom(1L, 10L)
        manager.joinRoom(2L, 20L)
        clearInvocations(chatRoomMemberRepository)

        manager.sendMessageToLocalRoom(
            roomId = 10L,
            message = ChatMessage(
                id = 100L,
                messageId = "msg-100",
                clientMessageId = "client-100",
                content = "hello",
                messageType = MessageType.TEXT,
                senderId = 1L,
                senderName = "sender",
                sequenceNumber = 1L,
                roomSeq = 1L,
                streamShard = 0,
                writeShard = 0,
                fanoutShard = 0,
                chatRoomId = 10L,
                timestamp = LocalDateTime.parse("2026-06-12T12:00:00"),
            ),
        )

        verify(sessionInRoom).sendMessage(any(TextMessage::class.java))
        verify(sessionOutsideRoom, never()).sendMessage(any(TextMessage::class.java))
        verify(chatRoomMemberRepository, never()).existsByChatRoomIdAndUserIdAndIsActiveTrue(anyLong(), anyLong())
    }

    @Test
    fun `force logout은 대상 user의 local session만 닫고 인덱스에서 제거한다`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        `when`(chatRoomMemberRepository.findActiveUserIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList<Long>())).thenAnswer { it.getArgument<List<Long>>(1) }
        val targetSession = session("target-session")
        val otherSession = session("other-session")
        val manager = sessionManager(chatRoomMemberRepository)

        manager.addSession(7L, targetSession)
        manager.addSession(8L, otherSession)

        manager.closeSessionsForUser(7L)

        verify(targetSession).close(any(CloseStatus::class.java))
        verify(otherSession, never()).close(any(CloseStatus::class.java))
        assertFalse(manager.sendTextToSession(targetSession, """{"type":"PING"}"""))
    }

    @Test
    fun `lost leave event cannot deliver to departed user or their other sessions`() {
        val members = mock(ChatRoomMemberRepository::class.java)
        `when`(members.existsByChatRoomIdAndUserIdAndIsActiveTrue(10, 7)).thenReturn(true)
        `when`(members.existsByChatRoomIdAndUserIdAndIsActiveTrue(10, 8)).thenReturn(true)
        val manager = sessionManager(members)
        val departed = session("departed")
        val second = session("second")
        val active = session("active")
        manager.addSession(7, departed)
        manager.addSession(7, second)
        manager.addSession(8, active)
        manager.joinRoom(7, 10)
        manager.joinRoom(8, 10)
        `when`(members.findActiveUserIds(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.anyList<Long>())).thenReturn(listOf(8))
        val message = com.chat.protocol.websocket.ChatMessageBatch(chatRoomId = 10, messages = emptyList())
        manager.sendMessageToLocalRoom(10, message)
        manager.sendMessageToLocalRoom(10, message)
        verify(departed, never()).sendMessage(any(TextMessage::class.java))
        verify(second, never()).sendMessage(any(TextMessage::class.java))
        verify(active, org.mockito.Mockito.times(2)).sendMessage(any(TextMessage::class.java))
        verify(members).findActiveUserIds(10, listOf(8))
    }

    @Test
    fun `membership store failure fails closed without enqueueing room data`() {
        val members = mock(ChatRoomMemberRepository::class.java)
        `when`(members.existsByChatRoomIdAndUserIdAndIsActiveTrue(10, 7)).thenReturn(true)
        val manager = sessionManager(members)
        val recipient = session("recipient")
        manager.addSession(7, recipient)
        manager.joinRoom(7, 10)
        `when`(members.findActiveUserIds(10, listOf(7))).thenThrow(org.springframework.dao.DataAccessResourceFailureException("offline"))
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException::class.java) {
            manager.sendMessageToLocalRoom(10, com.chat.protocol.websocket.ChatMessageBatch(chatRoomId = 10, messages = emptyList()))
        }
        verify(recipient, never()).sendMessage(any(TextMessage::class.java))
    }

    private fun session(id: String): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(true)
        return session
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionManager(chatRoomMemberRepository: ChatRoomMemberRepository): WebSocketSessionManager =
        sessionManagerFixture(chatRoomMemberRepository).manager

    @Suppress("UNCHECKED_CAST")
    private fun sessionManagerFixture(chatRoomMemberRepository: ChatRoomMemberRepository): SessionManagerFixture {
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val setOperations = mock(SetOperations::class.java) as SetOperations<String, String>
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(setOperations.isMember(anyString(), anyString())).thenReturn(false)

        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
        val redisProperties = ChatRedisProperties(
            serverRoomsKeyPrefix = "test:server:rooms:",
            broker = ChatRedisProperties.Broker(serverId = "test-server"),
        )
        val redisMessageBroker = RedisMessageBroker(
            redisTemplate = redisTemplate,
            messageListenerContainer = mock(RedisMessageListenerContainer::class.java),
            objectMapper = objectMapper,
            redisProperties = redisProperties,
        )

        val manager = WebSocketSessionManager(
            objectMapper = objectMapper,
            roomTransport = RedisGatewayRoomTransport(redisTemplate, redisProperties, redisMessageBroker),
            memberships = GatewayMembershipsAdapter(chatRoomMemberRepository),
            sessionControlEvents = mock(com.chat.core.gateway.port.SessionControlEvents::class.java),
            roomSubscriptions = WebSocketRoomSubscriptions(RedisGatewayRoomTransport(redisTemplate, redisProperties, redisMessageBroker)),
            transport = WebSocketSessionTransport(
                authorization = org.mockito.Mockito.mock(WebSocketSessionAuthorization::class.java),
                gatewayProperties = ChatWebSocketGatewayProperties(outboundQueueMaxPendingMessages = 128),
                outboundExecutor = Runnable::run,
            ),
        )

        return SessionManagerFixture(
            redisTemplate = redisTemplate,
            manager = manager,
            redisMessageBroker = redisMessageBroker,
            objectMapper = objectMapper,
        )
    }

    private data class SessionManagerFixture(
        val redisTemplate: RedisTemplate<String, String>,
        val manager: WebSocketSessionManager,
        val redisMessageBroker: RedisMessageBroker,
        val objectMapper: ObjectMapper,
    )
}
