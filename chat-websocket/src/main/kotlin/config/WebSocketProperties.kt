package com.chat.websocket.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.websocket")
data class WebSocketProperties(
    val endpoint: String = "/ws/chat",
    val allowedOrigins: List<String> = listOf("*"),
    val userIdAttribute: String = "userId",
    val initialChatRoomPageSize: Int = 100,
)
