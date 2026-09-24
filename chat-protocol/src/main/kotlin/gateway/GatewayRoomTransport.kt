package com.chat.protocol.gateway

import com.chat.protocol.websocket.WebSocketMessage

interface GatewayRoomTransport {
    fun getServerId(): String

    fun setLocalMessageHandler(handler: (Long, WebSocketMessage) -> Unit)

    fun setLocalMembershipHandler(handler: (MembershipChange) -> Unit)

    fun subscribeToRoom(roomId: Long)

    fun unsubscribeFromRoom(roomId: Long)

    /** Return false when the advisory index is unavailable; local registration must continue. */
    fun synchronizeServerRoom(roomId: Long, active: Boolean): Boolean
}

data class MembershipChange(val userId: Long, val roomId: Long, val action: MembershipAction)

enum class MembershipAction { JOIN, LEAVE }
