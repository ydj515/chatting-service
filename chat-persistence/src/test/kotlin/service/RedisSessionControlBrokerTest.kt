package com.chat.persistence.service

import com.chat.persistence.config.ChatAuthProperties
import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer

class RedisSessionControlBrokerTest {

    @Test
    fun `forceLogoutUser는 session control topic으로 이벤트를 발행한다`() {
        val redisTemplate = mockRedisTemplate()
        val broker = broker(redisTemplate = redisTemplate)

        broker.forceLogoutUser(7L, "suspended")

        verify(redisTemplate).convertAndSend(
            eq("chat.session.control"),
            contains("\"type\":\"FORCE_LOGOUT_USER\""),
        )
        verify(redisTemplate).convertAndSend(eq("chat.session.control"), contains("\"userId\":7"))
        verify(redisTemplate).convertAndSend(eq("chat.session.control"), contains("\"reason\":\"suspended\""))
    }

    @Test
    fun `remote force logout 이벤트를 local handler로 전달한다`() {
        val objectMapper = objectMapper()
        val broker = broker(objectMapper = objectMapper)
        var captured: Pair<Long, String>? = null
        broker.setLocalForceLogoutHandler { userId, reason ->
            captured = userId to reason
        }

        val event = RedisSessionControlBroker.SessionControlEvent(
            id = "remote-event",
            serverId = "remote-server",
            type = RedisSessionControlBroker.EventType.FORCE_LOGOUT_USER,
            userId = 7L,
            reason = "suspended",
            timestamp = java.time.LocalDateTime.parse("2026-06-27T00:00:00"),
        )

        broker.onMessage(
            DefaultMessage(
                "chat.session.control".toByteArray(),
                objectMapper.writeValueAsBytes(event),
            ),
            null,
        )

        assertEquals(7L to "suspended", captured)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockRedisTemplate(): RedisTemplate<String, String> {
        return mock(RedisTemplate::class.java) as RedisTemplate<String, String>
    }

    private fun broker(
        redisTemplate: RedisTemplate<String, String> = mockRedisTemplate(),
        objectMapper: ObjectMapper = objectMapper(),
    ): RedisSessionControlBroker {
        return RedisSessionControlBroker(
            redisTemplate = redisTemplate,
            messageListenerContainer = mock(RedisMessageListenerContainer::class.java),
            objectMapper = objectMapper,
            authProperties = ChatAuthProperties(
                session = ChatAuthProperties.Session(
                    secret = "test-secret",
                    controlTopic = "chat.session.control",
                )
            ),
            redisProperties = ChatRedisProperties(
                broker = ChatRedisProperties.Broker(serverId = "local-server"),
            ),
        )
    }

    private fun objectMapper(): ObjectMapper {
        return ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
    }
}
