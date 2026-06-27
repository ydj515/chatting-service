package com.chat.websocket.service

import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.service.WebSocketSessionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class WebSocketHeartbeatScheduler(
    private val sessionManager: WebSocketSessionManager,
    private val properties: ChatWebSocketGatewayProperties,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Scheduled(fixedDelayString = "\${chat.websocket.gateway.heartbeat-interval-millis:30000}")
    fun pollHeartbeat() {
        if (properties.heartbeatEnabled) {
            sessionManager.pollHeartbeats(clock.millis())
        }
    }
}
