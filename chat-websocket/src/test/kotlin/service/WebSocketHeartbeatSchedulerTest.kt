package com.chat.websocket.service

import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.service.WebSocketSessionManager
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebSocketHeartbeatSchedulerTest {

    @Test
    fun `heartbeat가 활성화되면 현재 시각으로 session manager를 poll한다`() {
        val sessionManager = mock(WebSocketSessionManager::class.java)
        val scheduler = WebSocketHeartbeatScheduler(
            sessionManager = sessionManager,
            properties = ChatWebSocketGatewayProperties(heartbeatEnabled = true),
            clock = Clock.fixed(Instant.ofEpochMilli(12_345), ZoneOffset.UTC),
        )

        scheduler.pollHeartbeat()

        verify(sessionManager).pollHeartbeats(12_345)
    }

    @Test
    fun `heartbeat가 비활성화되면 session manager를 poll하지 않는다`() {
        val sessionManager = mock(WebSocketSessionManager::class.java)
        val scheduler = WebSocketHeartbeatScheduler(
            sessionManager = sessionManager,
            properties = ChatWebSocketGatewayProperties(heartbeatEnabled = false),
            clock = Clock.fixed(Instant.ofEpochMilli(12_345), ZoneOffset.UTC),
        )

        scheduler.pollHeartbeat()

        verify(sessionManager, never()).pollHeartbeats(12_345)
    }
}
