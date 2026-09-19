package com.chat.persistence.service

import com.chat.persistence.config.ChatRoomPolicyProperties
import org.springframework.stereotype.Service

enum class RoomHeatLevel {
    NORMAL,
    HOT,
    VERY_HOT,
    OVERLOAD,
}

data class RoomTrafficSnapshot(
    val roomId: Long,
    val roomMessagesPerSecond: Long,
    val roomMessagesP95PerSecond: Long,
    val writerLagMillis: Long = 0,
    val fanoutLagMillis: Long = 0,
    val gatewaySendQueueDepth: Int = 0,
)

data class RoomHeatPolicy(
    val roomId: Long,
    val heatLevel: RoomHeatLevel,
    val liveFeedMaxMessages: Int,
    val liveFeedMaxAgeSeconds: Int,
    val roomRateLimitPerSecond: Int?,
    val slowModeSeconds: Int?,
    val writeShardCount: Int,
    val fanoutShardCount: Int,
)

@Service
class RoomHeatClassifier(
    private val properties: ChatRoomPolicyProperties,
) {
    fun classify(snapshot: RoomTrafficSnapshot): RoomHeatPolicy =
        when {
            snapshot.isOverload() -> RoomHeatPolicy(
                roomId = snapshot.roomId,
                heatLevel = RoomHeatLevel.OVERLOAD,
                liveFeedMaxMessages = properties.overloadLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.overloadLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = properties.overloadRoomRateLimitPerSecond,
                slowModeSeconds = properties.overloadSlowModeSeconds,
                writeShardCount = properties.veryHotShardCount,
                fanoutShardCount = properties.veryHotShardCount,
            )

            snapshot.isVeryHot() -> RoomHeatPolicy(
                roomId = snapshot.roomId,
                heatLevel = RoomHeatLevel.VERY_HOT,
                liveFeedMaxMessages = properties.veryHotLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.veryHotLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = properties.veryHotRoomRateLimitPerSecond,
                slowModeSeconds = properties.veryHotSlowModeSeconds,
                writeShardCount = properties.veryHotShardCount,
                fanoutShardCount = properties.veryHotShardCount,
            )

            snapshot.roomMessagesPerSecond >= properties.hotMessagesPerSecond -> RoomHeatPolicy(
                roomId = snapshot.roomId,
                heatLevel = RoomHeatLevel.HOT,
                liveFeedMaxMessages = properties.normalLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.normalLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = null,
                slowModeSeconds = properties.hotSlowModeSeconds,
                writeShardCount = properties.hotShardCount,
                fanoutShardCount = properties.hotShardCount,
            )

            else -> RoomHeatPolicy(
                roomId = snapshot.roomId,
                heatLevel = RoomHeatLevel.NORMAL,
                liveFeedMaxMessages = properties.normalLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.normalLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = null,
                slowModeSeconds = null,
                writeShardCount = MIN_SHARD_COUNT,
                fanoutShardCount = MIN_SHARD_COUNT,
            )
        }.sanitized()

    private fun RoomTrafficSnapshot.isVeryHot(): Boolean =
        roomMessagesPerSecond >= properties.veryHotMessagesPerSecond ||
            roomMessagesP95PerSecond >= properties.veryHotMessagesPerSecond

    private fun RoomTrafficSnapshot.isOverload(): Boolean =
        writerLagMillis > properties.overloadWriterLagMillis ||
            fanoutLagMillis > properties.overloadFanoutLagMillis ||
            gatewaySendQueueDepth > properties.overloadGatewayQueueDepth

    private fun RoomHeatPolicy.sanitized(): RoomHeatPolicy = copy(
        writeShardCount = writeShardCount.coerceAtLeast(MIN_SHARD_COUNT),
        fanoutShardCount = fanoutShardCount.coerceAtLeast(MIN_SHARD_COUNT),
    )

    private companion object {
        const val MIN_SHARD_COUNT = 1
    }
}
