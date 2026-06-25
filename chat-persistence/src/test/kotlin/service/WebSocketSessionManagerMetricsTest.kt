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
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.socket.WebSocketSession
import java.time.LocalDateTime
import java.util.stream.Stream

class WebSocketSessionManagerMetricsTest {

    @Test
    fun `local delivery counter and connection gauge reflect gateway activity`() {
        val registry = SimpleMeterRegistry()
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        `when`(chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L)).thenReturn(true)
        val manager = manager(registry, chatRoomMemberRepository)
        manager.initialize()

        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn("s1")
        `when`(session.isOpen).thenReturn(true)
        manager.addSession(7L, session)
        manager.joinRoom(7L, 10L)

        manager.sendMessageToLocalRoom(10L, sampleMessage())

        val delivered = registry.find("chat.websocket.gateway.local.deliveries").counter()?.count() ?: 0.0
        assertTrue(delivered >= 1.0, "expected at least one local delivery, got $delivered")
        assertEquals(
            1.0,
            registry.find("chat.websocket.gateway.connections").tag("gatewayGroup", "default").gauge()?.value(),
        )
    }

    private fun sampleMessage() = ChatMessage(
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

    @Suppress("UNCHECKED_CAST")
    private fun manager(registry: MeterRegistry, repo: ChatRoomMemberRepository): WebSocketSessionManager {
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
            chatRoomMemberRepository = repo,
            redisProperties = redisProperties,
            gatewayProperties = ChatWebSocketGatewayProperties(outboundQueueMaxPendingMessages = 128),
            outboundExecutor = Runnable::run,
            gatewayMetrics = WebSocketGatewayMetrics("default", provider(registry)),
        )
    }

    private fun provider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry = meterRegistry
            override fun getObject(vararg args: Any?): MeterRegistry = meterRegistry
            override fun getIfAvailable(): MeterRegistry = meterRegistry
            override fun getIfUnique(): MeterRegistry = meterRegistry
            override fun iterator(): MutableIterator<MeterRegistry> = mutableListOf(meterRegistry).iterator()
            override fun stream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
            override fun orderedStream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
        }
    }
}
