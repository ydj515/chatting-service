package com.chat.websocket.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class WebSocketHeartbeatScheduler(
    private val sessionManager: WebSocketSessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${chat.websocket.gateway.heartbeat-scheduler-poll-interval-millis:10000}", scheduler = "webSocketHeartbeatExecutor")
    fun pollHeartbeat() {
        // The transport controls ping enablement; index retries must always run.
        sessionManager.pollHeartbeats(clock.millis())
    }
}
