package com.chat.admin.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "chat.admin")
data class AdminProperties(
    @field:NotBlank
    val token: String,
    val actor: String = "admin-local",
    val defaultLimit: Int = 50,
    val maxLimit: Int = 100,
)
