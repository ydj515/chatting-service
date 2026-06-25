package com.chat.persistence.service

import com.chat.persistence.config.ChatRoomPolicyProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomHeatClassifierTest {

    @Test
    fun `NORMAL 방은 기본 live feed window를 유지한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 999,
                roomMessagesP95PerSecond = 999,
            ),
        )

        assertEquals(RoomHeatLevel.NORMAL, policy.heatLevel)
        assertEquals(1000, policy.liveFeedMaxMessages)
        assertEquals(60, policy.liveFeedMaxAgeSeconds)
        assertEquals(null, policy.roomRateLimitPerSecond)
        assertEquals(null, policy.slowModeSeconds)
    }

    @Test
    fun `HOT 방은 heat만 올리고 기본 live feed window를 유지한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 1000,
                roomMessagesP95PerSecond = 1000,
            ),
        )

        assertEquals(RoomHeatLevel.HOT, policy.heatLevel)
        assertEquals(1000, policy.liveFeedMaxMessages)
        assertEquals(60, policy.liveFeedMaxAgeSeconds)
        assertEquals(null, policy.roomRateLimitPerSecond)
        assertEquals(1, policy.slowModeSeconds)
    }

    @Test
    fun `HOT 방은 shard count를 16으로 확장한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 1000,
                roomMessagesP95PerSecond = 1000,
            ),
        )

        assertEquals(16, policy.writeShardCount)
        assertEquals(16, policy.fanoutShardCount)
    }

    @Test
    fun `VERY_HOT 방은 live feed를 500개 30초로 downgrade하고 room limit을 적용한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 5000,
                roomMessagesP95PerSecond = 3000,
            ),
        )

        assertEquals(RoomHeatLevel.VERY_HOT, policy.heatLevel)
        assertEquals(500, policy.liveFeedMaxMessages)
        assertEquals(30, policy.liveFeedMaxAgeSeconds)
        assertEquals(5000, policy.roomRateLimitPerSecond)
        assertEquals(1, policy.slowModeSeconds)
    }

    @Test
    fun `VERY_HOT 방은 shard count를 64로 확장한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 5000,
                roomMessagesP95PerSecond = 3000,
            ),
        )

        assertEquals(64, policy.writeShardCount)
        assertEquals(64, policy.fanoutShardCount)
    }

    @Test
    fun `p95가 VERY_HOT 임계치를 넘으면 현재 초당 메시지가 낮아도 VERY_HOT으로 분류한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 2000,
                roomMessagesP95PerSecond = 5000,
            ),
        )

        assertEquals(RoomHeatLevel.VERY_HOT, policy.heatLevel)
    }

    @Test
    fun `lag나 gateway queue가 임계치를 넘으면 OVERLOAD로 분류하고 live feed를 300개 15초로 낮춘다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 100,
                roomMessagesP95PerSecond = 100,
                writerLagMillis = 3001,
            ),
        )

        assertEquals(RoomHeatLevel.OVERLOAD, policy.heatLevel)
        assertEquals(300, policy.liveFeedMaxMessages)
        assertEquals(15, policy.liveFeedMaxAgeSeconds)
        assertEquals(1000, policy.roomRateLimitPerSecond)
        assertEquals(3, policy.slowModeSeconds)
    }

    @Test
    fun `OVERLOAD 방은 VERY_HOT과 같은 shard count를 유지한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 100,
                roomMessagesP95PerSecond = 100,
                fanoutLagMillis = 3001,
            ),
        )

        assertEquals(64, policy.writeShardCount)
        assertEquals(64, policy.fanoutShardCount)
    }

    private fun classifier(): RoomHeatClassifier {
        return RoomHeatClassifier(ChatRoomPolicyProperties())
    }
}
