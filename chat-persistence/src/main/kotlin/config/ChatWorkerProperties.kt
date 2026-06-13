package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.worker")
data class ChatWorkerProperties(
    val roles: Set<String> = setOf("message-writer", "fanout"),
    val consumerName: String = "worker-local",
    val pollDelayMillis: Long = 100,
    val writer: StreamConsumer = StreamConsumer(
        consumerGroup = "message-writer",
    ),
    val fanout: StreamConsumer = StreamConsumer(
        consumerGroup = "fanout",
    ),
) {
    fun roleEnabled(role: String): Boolean = roles.contains(role)

    data class StreamConsumer(
        val consumerGroup: String,
        val readCount: Long = 100,
        val minIdleMillis: Long = 30_000,
        val claimIntervalMillis: Long = 10_000,
        val maxDeliveryCount: Long = 5,
    )
}
