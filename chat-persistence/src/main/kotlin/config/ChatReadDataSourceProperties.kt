package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.datasource.read")
data class ChatReadDataSourceProperties(
    val enabled: Boolean = false,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val driverClassName: String = "org.postgresql.Driver",
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeout: Long = 30_000,
    val idleTimeout: Long = 600_000,
    val maxLifetime: Long = 1_800_000,
    val latestHistoryMaxReplicaLag: Duration = Duration.ofSeconds(2),
)
