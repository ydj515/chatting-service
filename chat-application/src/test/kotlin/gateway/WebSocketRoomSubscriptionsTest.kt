package com.chat.application.gateway

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.redis.RedisGatewayRoomTransport
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.websocket.service.WebSocketRoomSubscriptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations

class WebSocketRoomSubscriptionsTest {
    @Test
    fun `index outage does not prevent local cleanup and retry follows current membership`() {
        @Suppress("UNCHECKED_CAST")
        val redis = mock(RedisTemplate::class.java) as RedisTemplate<String, String>

        @Suppress("UNCHECKED_CAST")
        val sets = mock(SetOperations::class.java) as SetOperations<String, String>
        `when`(redis.opsForSet()).thenReturn(sets)
        val broker = mock(RedisMessageBroker::class.java)
        `when`(broker.getServerId()).thenReturn("server")
        val subscriptions = WebSocketRoomSubscriptions(RedisGatewayRoomTransport(redis, ChatRedisProperties(), broker))
        subscriptions.addSessionIdToRoom(10, "old")
        doThrow(RedisConnectionFailureException("unavailable")).doReturn(1L).`when`(sets).remove("chat:server:rooms:server", "10")
        subscriptions.removeSessionIdFromRoom(10, "old")
        assertEquals(0, subscriptions.subscriptionCount())
        assertNull(subscriptions.sessionIds(10))
        verify(broker).unsubscribeFromRoom(10)
        subscriptions.retryIndexUpdates()
        verify(sets, times(2)).remove("chat:server:rooms:server", "10")
        subscriptions.addSessionIdToRoom(10, "new")
        subscriptions.retryIndexUpdates()
        assertEquals(listOf("new"), subscriptions.sessionIds(10))
        verify(broker, times(2)).subscribeToRoom(10)
        verify(sets, times(2)).remove("chat:server:rooms:server", "10")
    }
}
