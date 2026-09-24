package com.chat.persistence.config

import com.chat.core.room.policy.RoomHeatClassifier
import com.chat.core.room.policy.RoomHeatSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RoomPolicyConfig {
    @Bean
    fun roomHeatClassifier(properties: ChatRoomPolicyProperties): RoomHeatClassifier = RoomHeatClassifier(properties.heatSettings())
}

internal fun ChatRoomPolicyProperties.heatSettings(): RoomHeatSettings = RoomHeatSettings(
    hotMessagesPerSecond = hotMessagesPerSecond,
    veryHotMessagesPerSecond = veryHotMessagesPerSecond,
    hotShardCount = hotShardCount,
    veryHotShardCount = veryHotShardCount,
    overloadWriterLagMillis = overloadWriterLagMillis,
    overloadFanoutLagMillis = overloadFanoutLagMillis,
    overloadGatewayQueueDepth = overloadGatewayQueueDepth,
    normalLiveFeedMaxMessages = normalLiveFeedMaxMessages,
    normalLiveFeedMaxAgeSeconds = normalLiveFeedMaxAgeSeconds,
    veryHotLiveFeedMaxMessages = veryHotLiveFeedMaxMessages,
    veryHotLiveFeedMaxAgeSeconds = veryHotLiveFeedMaxAgeSeconds,
    overloadLiveFeedMaxMessages = overloadLiveFeedMaxMessages,
    overloadLiveFeedMaxAgeSeconds = overloadLiveFeedMaxAgeSeconds,
    veryHotRoomRateLimitPerSecond = veryHotRoomRateLimitPerSecond,
    overloadRoomRateLimitPerSecond = overloadRoomRateLimitPerSecond,
    hotSlowModeSeconds = hotSlowModeSeconds,
    veryHotSlowModeSeconds = veryHotSlowModeSeconds,
    overloadSlowModeSeconds = overloadSlowModeSeconds,
)
