package com.chat.persistence.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "chat.room-policy")
data class ChatRoomPolicyProperties(
    val hotMessagesPerSecond: Long = 1_000,
    val veryHotMessagesPerSecond: Long = 5_000,
    @field:Min(1)
    val hotShardCount: Int = 16,
    @field:Min(1)
    val veryHotShardCount: Int = 64,
    val overloadWriterLagMillis: Long = 3_000,
    val overloadFanoutLagMillis: Long = 3_000,
    val overloadGatewayQueueDepth: Int = 128,
    val normalLiveFeedMaxMessages: Int = 1_000,
    val normalLiveFeedMaxAgeSeconds: Int = 60,
    val veryHotLiveFeedMaxMessages: Int = 500,
    val veryHotLiveFeedMaxAgeSeconds: Int = 30,
    val overloadLiveFeedMaxMessages: Int = 300,
    val overloadLiveFeedMaxAgeSeconds: Int = 15,
    val veryHotRoomRateLimitPerSecond: Int = 5_000,
    val overloadRoomRateLimitPerSecond: Int = 1_000,
    val hotSlowModeSeconds: Int = 1,
    val veryHotSlowModeSeconds: Int = 1,
    val overloadSlowModeSeconds: Int = 3,
    val trafficKeyPrefix: String = "chat:room-traffic:",
    val activeRoomsKey: String = "chat:room-traffic:active-rooms",
    val trafficWindowSeconds: Long = 60,
    val trafficCounterTtlSeconds: Long = 120,
) {
    @get:AssertTrue(message = "veryHotShardCount must be greater than or equal to hotShardCount")
    val veryHotShardCountAtLeastHotShardCount: Boolean
        get() = veryHotShardCount >= hotShardCount
}
