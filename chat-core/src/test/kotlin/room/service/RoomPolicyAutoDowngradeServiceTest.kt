package com.chat.core.room.service

import com.chat.core.room.policy.RoomHeatClassifier
import com.chat.core.room.policy.RoomHeatLevel
import com.chat.core.room.policy.RoomHeatPolicy
import com.chat.core.room.policy.RoomHeatSettings
import com.chat.core.room.policy.RoomTrafficSnapshot
import com.chat.core.room.port.RoomPolicyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomPolicyAutoDowngradeServiceTest {
    @Test
    fun `traffic snapshot을 heat policy로 분류한 뒤 room policy repository에 적용한다`() {
        val repository = RecordingRoomPolicyRepository()
        val service = RoomPolicyAutoDowngradeService(
            roomHeatClassifier = RoomHeatClassifier(RoomHeatSettings()),
            roomPolicyRepository = repository,
        )

        val policy = service.applyDowngradePolicy(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 5000,
                roomMessagesP95PerSecond = 5000,
            ),
        )

        assertEquals(RoomHeatLevel.VERY_HOT, policy.heatLevel)
        assertEquals(policy, repository.appliedPolicy)
    }

    private class RecordingRoomPolicyRepository : RoomPolicyRepository {
        var appliedPolicy: RoomHeatPolicy? = null
            private set

        override fun applyAutomaticPolicy(policy: RoomHeatPolicy) {
            appliedPolicy = policy
        }
    }
}
