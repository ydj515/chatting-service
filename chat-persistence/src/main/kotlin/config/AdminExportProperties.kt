package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chat.admin.export")
data class AdminExportProperties(
    val directory: String = "build/admin-exports",
    val chunkSize: Int = 1_000,
)
