package com.chat.persistence.service

data class RoomAdmissionPolicy(
    val roomRateLimitPerSecond: Int? = null,
    val userRateLimitPerSecond: Int? = null,
    val slowModeSeconds: Int? = null,
    val moderatorPriority: Boolean = true,
)

interface RoomAdmissionPolicyReader {
    fun admissionPolicy(roomId: Long): RoomAdmissionPolicy
}
