package com.chat.core.room.service

import com.chat.core.room.policy.RoomHeatClassifier
import com.chat.core.room.policy.RoomHeatPolicy
import com.chat.core.room.policy.RoomTrafficSnapshot
import com.chat.core.room.port.RoomPolicyRepository
import org.springframework.stereotype.Service

@Service
class RoomPolicyAutoDowngradeService(
    private val roomHeatClassifier: RoomHeatClassifier,
    private val roomPolicyRepository: RoomPolicyRepository,
) {
    fun applyDowngradePolicy(snapshot: RoomTrafficSnapshot): RoomHeatPolicy {
        val policy = roomHeatClassifier.classify(snapshot)
        roomPolicyRepository.applyAutomaticPolicy(policy)
        return policy
    }
}
