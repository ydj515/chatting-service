package com.chat.persistence.service

import org.springframework.stereotype.Service

interface RoomPolicyRepository {
    fun applyAutomaticPolicy(policy: RoomHeatPolicy)
}

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
