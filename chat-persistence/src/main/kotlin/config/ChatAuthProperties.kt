package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.auth")
data class ChatAuthProperties(
    val session: Session = Session(),
) {
    data class Session(
        val secret: String = "local-development-session-secret-change-me",
        val ttl: Duration = Duration.ofHours(12),
        val tokenQueryParam: String = "token",
    )
}
