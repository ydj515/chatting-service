package com.chat.persistence.config

import com.chat.core.room.policy.RoomHeatSettings
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "chat.room-policy")
data class ChatRoomPolicyProperties(
    val hotMessagesPerSecond: Long = DEFAULT_HEAT_SETTINGS.hotMessagesPerSecond,
    val veryHotMessagesPerSecond: Long = DEFAULT_HEAT_SETTINGS.veryHotMessagesPerSecond,
    @field:Min(1)
    val hotShardCount: Int = DEFAULT_HEAT_SETTINGS.hotShardCount,
    @field:Min(1)
    val veryHotShardCount: Int = DEFAULT_HEAT_SETTINGS.veryHotShardCount,
    val overloadWriterLagMillis: Long = DEFAULT_HEAT_SETTINGS.overloadWriterLagMillis,
    val overloadFanoutLagMillis: Long = DEFAULT_HEAT_SETTINGS.overloadFanoutLagMillis,
    val overloadGatewayQueueDepth: Int = DEFAULT_HEAT_SETTINGS.overloadGatewayQueueDepth,
    val normalLiveFeedMaxMessages: Int = DEFAULT_HEAT_SETTINGS.normalLiveFeedMaxMessages,
    val normalLiveFeedMaxAgeSeconds: Int = DEFAULT_HEAT_SETTINGS.normalLiveFeedMaxAgeSeconds,
    val veryHotLiveFeedMaxMessages: Int = DEFAULT_HEAT_SETTINGS.veryHotLiveFeedMaxMessages,
    val veryHotLiveFeedMaxAgeSeconds: Int = DEFAULT_HEAT_SETTINGS.veryHotLiveFeedMaxAgeSeconds,
    val overloadLiveFeedMaxMessages: Int = DEFAULT_HEAT_SETTINGS.overloadLiveFeedMaxMessages,
    val overloadLiveFeedMaxAgeSeconds: Int = DEFAULT_HEAT_SETTINGS.overloadLiveFeedMaxAgeSeconds,
    val veryHotRoomRateLimitPerSecond: Int = DEFAULT_HEAT_SETTINGS.veryHotRoomRateLimitPerSecond,
    val overloadRoomRateLimitPerSecond: Int = DEFAULT_HEAT_SETTINGS.overloadRoomRateLimitPerSecond,
    val hotSlowModeSeconds: Int = DEFAULT_HEAT_SETTINGS.hotSlowModeSeconds,
    val veryHotSlowModeSeconds: Int = DEFAULT_HEAT_SETTINGS.veryHotSlowModeSeconds,
    val overloadSlowModeSeconds: Int = DEFAULT_HEAT_SETTINGS.overloadSlowModeSeconds,
    val trafficKeyPrefix: String = "chat:room-traffic:",
    val activeRoomsKey: String = "chat:room-traffic:active-rooms",
    val trafficWindowSeconds: Long = 60,
    val trafficCounterTtlSeconds: Long = 120,
) {
    @get:AssertTrue(message = "veryHotShardCount must be greater than or equal to hotShardCount")
    val veryHotShardCountAtLeastHotShardCount: Boolean
        get() = veryHotShardCount >= hotShardCount
}

private val DEFAULT_HEAT_SETTINGS = RoomHeatSettings()
