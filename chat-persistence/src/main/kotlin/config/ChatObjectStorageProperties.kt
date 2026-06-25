package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.object-storage")
data class ChatObjectStorageProperties(
    val enabled: Boolean = false,
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val bucket: String = "chat-archives",
    val accessKey: String = "chatminio",
    val secretKey: String = "chatminiosecret",
    val pathStyleAccess: Boolean = true,
    val adminExportPrefix: String = "admin-exports",
    val presignedUrlTtl: Duration = Duration.ofMinutes(15),
)
