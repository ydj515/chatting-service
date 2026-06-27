package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.WebSocketSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebSocketSessionManagerHeartbeatTest {

    @Test
    fun `heartbeat poll은 열린 세션에 ping frame을 전송한다`() {
        val session = session("session-1")
        val manager = manager(
            clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            properties = ChatWebSocketGatewayProperties(
                heartbeatIntervalMillis = 30_000,
                heartbeatTimeoutMillis = 90_000,
            ),
        )
        manager.addSession(7L, session)

        manager.pollHeartbeats(nowMillis = 31_000)

        verify(session).sendMessage(any(PingMessage::class.java))
    }

    @Test
    fun `heartbeat timeout을 넘긴 세션은 닫고 인덱스에서 제거한다`() {
        val session = session("session-1")
        val manager = manager(
            clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            properties = ChatWebSocketGatewayProperties(
                heartbeatIntervalMillis = 30_000,
                heartbeatTimeoutMillis = 90_000,
            ),
        )
        manager.addSession(7L, session)
        clearInvocations(session)

        manager.pollHeartbeats(nowMillis = 91_001)

        verify(session).close(any(CloseStatus::class.java))
        assertFalse(manager.sendTextToSession(session, """{"type":"PING"}"""))
    }

    @Test
    fun `pong이나 inbound 메시지 활동은 heartbeat timeout 기준을 갱신한다`() {
        val session = session("session-1")
        val manager = manager(
            clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            properties = ChatWebSocketGatewayProperties(
                heartbeatIntervalMillis = 30_000,
                heartbeatTimeoutMillis = 90_000,
            ),
        )
        manager.addSession(7L, session)
        manager.recordSessionActivity(session, nowMillis = 90_000)

        manager.pollHeartbeats(nowMillis = 91_001)

        verify(session, never()).close(any(CloseStatus::class.java))
    }

    @Suppress("UNCHECKED_CAST")
    private fun manager(
        clock: Clock,
        properties: ChatWebSocketGatewayProperties,
    ): WebSocketSessionManager {
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val setOperations = mock(SetOperations::class.java) as SetOperations<String, String>
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)

        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
        val redisProperties = ChatRedisProperties(
            serverRoomsKeyPrefix = "test:server:rooms:",
            broker = ChatRedisProperties.Broker(serverId = "test-server"),
        )
        val broker = RedisMessageBroker(
            redisTemplate = redisTemplate,
            messageListenerContainer = mock(RedisMessageListenerContainer::class.java),
            objectMapper = objectMapper,
            redisProperties = redisProperties,
        )

        return WebSocketSessionManager(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisMessageBroker = broker,
            chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java),
            redisProperties = redisProperties,
            gatewayProperties = properties,
            outboundExecutor = Runnable::run,
            clock = clock,
        )
    }

    private fun session(id: String): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(true)
        return session
    }
}
