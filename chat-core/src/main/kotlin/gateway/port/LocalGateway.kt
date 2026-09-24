package com.chat.core.gateway.port

/** Local capabilities are present only in processes that host WebSocket connections. */
interface LocalGateway {
    fun currentSendQueueDepth(): Int

    fun isUserOnlineLocally(userId: Long): Boolean

    fun joinRoom(userId: Long, roomId: Long)

    fun leaveRoom(userId: Long, roomId: Long)
}
