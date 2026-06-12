package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.websocket.gateway")
data class ChatWebSocketGatewayProperties(
    val outboundQueueMaxPendingMessages: Int = 128,
    val outboundExecutorThreads: Int = 32,
    val outboundSendTimeLimitMillis: Int = 10_000,
    val outboundSendBufferSizeLimitBytes: Int = 512 * 1024,
)
