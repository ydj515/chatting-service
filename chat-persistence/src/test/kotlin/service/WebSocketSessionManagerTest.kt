package com.chat.persistence.service

import com.chat.domain.dto.ChatMessage
import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
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
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.LocalDateTime

class WebSocketSessionManagerTest {

    @Test
    fun `원격 JOIN membership event는 열린 local session을 방 인덱스에 추가한다`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
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
            )
        )

        verify(session).sendMessage(any(TextMessage::class.java))
    }

    @Test
    fun `room fan-out은 해당 방 local session만 순회하고 DB membership을 조회하지 않는다`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
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
            )
        )

        verify(sessionInRoom).sendMessage(any(TextMessage::class.java))
        verify(sessionOutsideRoom, never()).sendMessage(any(TextMessage::class.java))
        verify(chatRoomMemberRepository, never()).existsByChatRoomIdAndUserIdAndIsActiveTrue(anyLong(), anyLong())
    }

    private fun session(id: String): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(true)
        return session
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionManager(chatRoomMemberRepository: ChatRoomMemberRepository): WebSocketSessionManager {
        return sessionManagerFixture(chatRoomMemberRepository).manager
    }

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
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisMessageBroker = redisMessageBroker,
            chatRoomMemberRepository = chatRoomMemberRepository,
            redisProperties = redisProperties,
            gatewayProperties = ChatWebSocketGatewayProperties(outboundQueueMaxPendingMessages = 128),
            outboundExecutor = Runnable::run,
        )

        return SessionManagerFixture(
            manager = manager,
            redisMessageBroker = redisMessageBroker,
            objectMapper = objectMapper,
        )
    }

    private data class SessionManagerFixture(
        val manager: WebSocketSessionManager,
        val redisMessageBroker: RedisMessageBroker,
        val objectMapper: ObjectMapper,
    )
}
