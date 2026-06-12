package com.chat.api.config

import com.chat.domain.dto.MessageDirection
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.message.pagination")
data class MessagePaginationProperties(
    val defaultLimit: Int = 50,
    val maxLimit: Int = 100,
    val defaultDirection: MessageDirection = MessageDirection.BEFORE,
)
