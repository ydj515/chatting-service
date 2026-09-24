package com.chat.websocket.service

import com.chat.websocket.service.WebSocketSessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebSocketHeartbeatSchedulerTest {
    @Test
    fun `scheduler fixed delay는 heartbeat interval과 별도 poll interval 설정을 사용한다`() {
        val annotation = WebSocketHeartbeatScheduler::class.java
            .getDeclaredMethod("pollHeartbeat")
            .getAnnotation(Scheduled::class.java)

        assertEquals(
            "\${chat.websocket.gateway.heartbeat-scheduler-poll-interval-millis:10000}",
            annotation.fixedDelayString,
        )
    }

    @Test
    fun `session maintenance always polls with the injected clock`() {
        val sessionManager = mock(WebSocketSessionManager::class.java)
        val scheduler = WebSocketHeartbeatScheduler(
            sessionManager = sessionManager,
            clock = Clock.fixed(Instant.ofEpochMilli(12_345), ZoneOffset.UTC),
        )

        scheduler.pollHeartbeat()

        verify(sessionManager).pollHeartbeats(12_345)
    }
}
