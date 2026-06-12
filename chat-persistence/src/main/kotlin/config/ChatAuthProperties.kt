package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.auth")
data class ChatAuthProperties(
    val session: Session = Session(),
    val webSocketTicket: WebSocketTicket = WebSocketTicket(),
) {
    data class Session(
        val secret: String = "local-development-session-secret-change-me",
        val ttl: Duration = Duration.ofHours(12),
        val tokenQueryParam: String = "token",
    )

    data class WebSocketTicket(
        val ttl: Duration = Duration.ofSeconds(30),
        val ticketQueryParam: String = "ticket",
        val keyPrefix: String = "chat:ws-ticket:",
        val rateLimitKeyPrefix: String = "chat:ws-ticket:rate:",
        val issueRateLimitWindow: Duration = Duration.ofMinutes(1),
        val issueRateLimitPerUser: Long = 10,
        val issueRateLimitPerIp: Long = 60,
        val sessionFallbackEnabled: Boolean = true,
    )
}
