package com.chat.websocket.service

import com.chat.core.gateway.port.GatewayRoomTransport
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WebSocketRoomSubscriptions(
    private val roomTransport: GatewayRoomTransport,
) {
    private val pendingIndexUpdates = ConcurrentHashMap.newKeySet<Long>()
    private val sessionIdsByRoomId = ConcurrentHashMap<Long, MutableSet<String>>()

    fun subscriptionCount(): Int = sessionIdsByRoomId.values.sumOf { it.size }

    fun sessionIds(roomId: Long): List<String>? = sessionIdsByRoomId[roomId]?.toList()

    fun addSessionIdToRoom(roomId: Long, sessionId: String) {
        sessionIdsByRoomId.compute(roomId) { _, sessionIds ->
            val nextSessionIds = sessionIds ?: ConcurrentHashMap.newKeySet()
            val wasEmpty = nextSessionIds.isEmpty()
            nextSessionIds.add(sessionId)
            if (wasEmpty) {
                roomTransport.subscribeToRoom(roomId)
                synchronizeServerRoom(roomId, active = true)
            }
            nextSessionIds
        }
    }

    fun removeSessionIdFromRoom(roomId: Long, sessionId: String) {
        sessionIdsByRoomId.computeIfPresent(roomId) { _, sessionIds ->
            sessionIds.remove(sessionId)
            if (sessionIds.isEmpty()) {
                roomTransport.unsubscribeFromRoom(roomId)
                synchronizeServerRoom(roomId, active = false)
                null
            } else {
                sessionIds
            }
        }
    }

    fun retryIndexUpdates() {
        for (roomId in pendingIndexUpdates.take(100)) {
            var synchronized = false
            sessionIdsByRoomId.compute(roomId) { _, sessionIds ->
                synchronized = synchronizeServerRoom(roomId, active = !sessionIds.isNullOrEmpty())
                sessionIds
            }
            if (!synchronized) return
        }
    }

    private fun synchronizeServerRoom(roomId: Long, active: Boolean): Boolean {
        val synchronized = roomTransport.synchronizeServerRoom(roomId, active)
        if (synchronized) pendingIndexUpdates.remove(roomId) else pendingIndexUpdates.add(roomId)
        return synchronized
    }
}
