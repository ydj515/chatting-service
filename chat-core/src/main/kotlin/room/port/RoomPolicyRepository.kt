package com.chat.core.room.port

import com.chat.core.room.policy.RoomHeatPolicy

interface RoomPolicyRepository {
    fun applyAutomaticPolicy(policy: RoomHeatPolicy)
}
