package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.message.sequence")
data class MessageSequenceProperties(
    val ttl: Duration = Duration.ofHours(24),
    val blockSize: Int = 1_000,
)
