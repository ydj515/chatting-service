package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("chat.cache.sanction-retry")
data class SanctionCacheRetryProperties(
    val batchSize: Int = 100,
    val leaseMillis: Long = 30_000,
    val retryDelayMillis: Long = 5_000,
    val maxRetryDelayMillis: Long = 300_000,
) {
    init {
        require(batchSize in 1..1_000) { "batchSize must be between 1 and 1000" }
        require(leaseMillis > 0 && retryDelayMillis > 0 && maxRetryDelayMillis >= retryDelayMillis) { "Invalid sanction cache retry timing" }
    }

    fun retryDelay(attempts: Int): Long {
        val multiplier = 1L shl attempts.coerceIn(0, 10)
        return if (retryDelayMillis > maxRetryDelayMillis / multiplier) maxRetryDelayMillis else retryDelayMillis * multiplier
    }
}
