package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.websocket.gateway")
data class ChatWebSocketGatewayProperties(
    val outboundQueueMaxPendingMessages: Int = 128,
    val outboundExecutorThreads: Int = 32,
    val outboundSendTimeLimitMillis: Int = 10_000,
    val outboundSendBufferSizeLimitBytes: Int = 512 * 1024,
    val heartbeatEnabled: Boolean = true,
    val heartbeatIntervalMillis: Long = 30_000,
    val heartbeatSchedulerPollIntervalMillis: Long = 10_000,
    val heartbeatTimeoutMillis: Long = 90_000,
    // metric의 gatewayGroup tag 값. cardinality 통제를 위해 bounded 값만 쓴다.
    val gatewayGroup: String = "default",
) {
    init {
        require(heartbeatIntervalMillis > 0) {
            "chat.websocket.gateway.heartbeat-interval-millis must be > 0"
        }
        require(heartbeatSchedulerPollIntervalMillis > 0) {
            "chat.websocket.gateway.heartbeat-scheduler-poll-interval-millis must be > 0"
        }
        require(heartbeatSchedulerPollIntervalMillis < heartbeatIntervalMillis) {
            "chat.websocket.gateway.heartbeat-scheduler-poll-interval-millis must be less than heartbeat-interval-millis"
        }
        require(heartbeatTimeoutMillis > heartbeatIntervalMillis) {
            "chat.websocket.gateway.heartbeat-timeout-millis must be greater than heartbeat-interval-millis"
        }
    }
}
