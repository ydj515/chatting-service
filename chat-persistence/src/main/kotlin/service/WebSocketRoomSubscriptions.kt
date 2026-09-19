package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.redis.RedisMessageBroker
import org.slf4j.LoggerFactory
import org.springframework.core.NestedRuntimeException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WebSocketRoomSubscriptions(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
    private val redisMessageBroker: RedisMessageBroker,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
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
                redisMessageBroker.subscribeToRoom(roomId)
                synchronizeServerRoom(roomId, active = true)
            }
            nextSessionIds
        }
    }

    fun removeSessionIdFromRoom(roomId: Long, sessionId: String) {
        sessionIdsByRoomId.computeIfPresent(roomId) { _, sessionIds ->
            sessionIds.remove(sessionId)
            if (sessionIds.isEmpty()) {
                redisMessageBroker.unsubscribeFromRoom(roomId)
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

    private fun synchronizeServerRoom(roomId: Long, active: Boolean): Boolean = try {
        val key = serverRoomKey(redisMessageBroker.getServerId())
        if (active) redisTemplate.opsForSet().add(key, roomId.toString()) else redisTemplate.opsForSet().remove(key, roomId.toString())
        pendingIndexUpdates.remove(roomId)
        true
    } catch (failure: NestedRuntimeException) {
        // Redis index availability must not interrupt local registration or broker cleanup.
        pendingIndexUpdates.add(roomId)
        logger.warn("Unable to synchronize server room index for {}", roomId, failure)
        false
    }

    private fun serverRoomKey(serverId: String): String = "${redisProperties.serverRoomsKeyPrefix}$serverId"
}
