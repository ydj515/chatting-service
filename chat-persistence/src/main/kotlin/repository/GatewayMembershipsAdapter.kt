package com.chat.persistence.repository

import com.chat.core.gateway.port.GatewayMemberships
import org.springframework.stereotype.Component

@Component
class GatewayMembershipsAdapter(private val members: ChatRoomMemberRepository) : GatewayMemberships {
    override fun isMember(roomId: Long, userId: Long): Boolean = members.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)

    override fun activeUserIds(roomId: Long, userIds: List<Long>): List<Long> = members.findActiveUserIds(roomId, userIds)
}
