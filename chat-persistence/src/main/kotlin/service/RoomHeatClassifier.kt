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
)

@Service
class RoomHeatClassifier(
    private val properties: ChatRoomPolicyProperties,
) {

    fun classify(snapshot: RoomTrafficSnapshot): RoomHeatPolicy {
        return when {
            snapshot.isOverload() -> snapshot.policy(
                heatLevel = RoomHeatLevel.OVERLOAD,
                liveFeedMaxMessages = properties.overloadLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.overloadLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = properties.overloadRoomRateLimitPerSecond,
                slowModeSeconds = properties.overloadSlowModeSeconds,
            )

            snapshot.isVeryHot() -> snapshot.policy(
                heatLevel = RoomHeatLevel.VERY_HOT,
                liveFeedMaxMessages = properties.veryHotLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.veryHotLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = properties.veryHotRoomRateLimitPerSecond,
                slowModeSeconds = properties.veryHotSlowModeSeconds,
            )

            snapshot.roomMessagesPerSecond >= properties.hotMessagesPerSecond -> snapshot.policy(
                heatLevel = RoomHeatLevel.HOT,
                liveFeedMaxMessages = properties.normalLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.normalLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = null,
                slowModeSeconds = properties.hotSlowModeSeconds,
            )

            else -> snapshot.policy(
                heatLevel = RoomHeatLevel.NORMAL,
                liveFeedMaxMessages = properties.normalLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.normalLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = null,
                slowModeSeconds = null,
            )
        }
    }

    private fun RoomTrafficSnapshot.isVeryHot(): Boolean {
        return roomMessagesPerSecond >= properties.veryHotMessagesPerSecond ||
            roomMessagesP95PerSecond >= properties.veryHotMessagesPerSecond
    }

    private fun RoomTrafficSnapshot.isOverload(): Boolean {
        return writerLagMillis > properties.overloadWriterLagMillis ||
            fanoutLagMillis > properties.overloadFanoutLagMillis ||
            gatewaySendQueueDepth > properties.overloadGatewayQueueDepth
    }

    private fun RoomTrafficSnapshot.policy(
        heatLevel: RoomHeatLevel,
        liveFeedMaxMessages: Int,
        liveFeedMaxAgeSeconds: Int,
        roomRateLimitPerSecond: Int?,
        slowModeSeconds: Int?,
    ): RoomHeatPolicy {
        return RoomHeatPolicy(
            roomId = roomId,
            heatLevel = heatLevel,
            liveFeedMaxMessages = liveFeedMaxMessages,
            liveFeedMaxAgeSeconds = liveFeedMaxAgeSeconds,
            roomRateLimitPerSecond = roomRateLimitPerSecond,
            slowModeSeconds = slowModeSeconds,
        )
    }
}
