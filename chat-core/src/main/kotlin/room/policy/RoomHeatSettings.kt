package com.chat.core.room.policy

data class RoomHeatSettings(
    val hotMessagesPerSecond: Long = 1_000,
    val veryHotMessagesPerSecond: Long = 5_000,
    val hotShardCount: Int = 16,
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
)
