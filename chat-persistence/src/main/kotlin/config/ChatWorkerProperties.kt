package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.worker")
data class ChatWorkerProperties(
    val roles: Set<String> = setOf(
        "message-writer",
        "fanout",
        "admin-export",
        "room-policy",
        "room-seq-gap-audit",
    ),
    val consumerName: String = "worker-local",
    val pollDelayMillis: Long = 100,
    val writer: StreamConsumer = StreamConsumer(
        consumerGroup = "message-writer",
    ),
    val fanout: StreamConsumer = StreamConsumer(
        consumerGroup = "fanout",
    ),
    val redisStreamLag: RedisStreamLag = RedisStreamLag(),
    val roomSeqGapAudit: RoomSeqGapAudit = RoomSeqGapAudit(),
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

    data class RoomSeqGapAudit(
        val enabled: Boolean = true,
        val pollDelayMillis: Long = 60_000,
        val lookback: Duration = Duration.ofMinutes(5),
    )
}
