package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.worker")
data class ChatWorkerProperties(
    val roles: Set<String> = setOf("message-writer", "fanout", "admin-export", "room-policy"),
    val consumerName: String = "worker-local",
    val pollDelayMillis: Long = 100,
    val writer: StreamConsumer = StreamConsumer(
        consumerGroup = "message-writer",
    ),
    val fanout: StreamConsumer = StreamConsumer(
        consumerGroup = "fanout",
    ),
    val redisStreamLag: RedisStreamLag = RedisStreamLag(),
) {
    fun roleEnabled(role: String): Boolean = roles.contains(role)

    data class StreamConsumer(
        val consumerGroup: String,
        val readCount: Long = 100,
        val minIdleMillis: Long = 30_000,
        val claimIntervalMillis: Long = 10_000,
        val maxDeliveryCount: Long = 5,
        val ownerLease: FanoutOwnerLease = FanoutOwnerLease(),
    )

    data class FanoutOwnerLease(
        val enabled: Boolean = true,
        val ttlMillis: Long = 10_000,
        val renewIntervalMillis: Long = 3_000,
        val keyPrefix: String = "chat:fanout:owner:room:",
    )

    data class RedisStreamLag(
        val enabled: Boolean = true,
        val pollDelayMillis: Long = 5_000,
    )
}
