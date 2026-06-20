package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.redis")
data class ChatRedisProperties(
    val sequenceKeyPrefix: String = "chat:sequence",
    val serverRoomsKeyPrefix: String = "chat:server:rooms:",
    val roomTopicPrefix: String = "chat.room.",
    val membershipTopic: String = "chat.membership",
    val broker: Broker = Broker(),
    val streams: Streams = Streams(),
    val admission: Admission = Admission(),
) {
    data class Broker(
        val serverId: String? = null,
        val cleanupInitialDelay: Duration = Duration.ofSeconds(30),
        val processedMessageTtl: Duration = Duration.ofMinutes(1),
        val processedMessageMaxSize: Int = 10_000,
    )

    data class Streams(
        val roomStreamKeyPrefix: String = "chat:stream:room:",
        val knownStreamsKey: String = "chat:stream:rooms",
        val deadLetterStreamKeyPrefix: String = "chat:stream:dlq:",
        val shardCount: Int = 1,
    )

    data class Admission(
        val keyPrefix: String = "chat:admission:room:",
        val rateLimitWindowTtl: Duration = Duration.ofSeconds(2),
    )
}
