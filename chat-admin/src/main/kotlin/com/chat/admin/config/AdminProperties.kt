package com.chat.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.admin")
data class AdminProperties(
    val token: String = "local-admin-token",
    val actor: String = "admin-local",
    val defaultLimit: Int = 50,
    val maxLimit: Int = 100,
)
