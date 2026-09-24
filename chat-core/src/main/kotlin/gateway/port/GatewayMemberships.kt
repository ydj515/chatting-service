package com.chat.core.gateway.port

interface GatewayMemberships {
    fun isMember(roomId: Long, userId: Long): Boolean

    fun activeUserIds(roomId: Long, userIds: List<Long>): List<Long>
}
