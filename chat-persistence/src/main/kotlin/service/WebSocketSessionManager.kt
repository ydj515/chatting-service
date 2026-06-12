package com.chat.persistence.service

import com.chat.domain.dto.ChatMessage
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

@Service
class WebSocketSessionManager(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val redisMessageBroker: RedisMessageBroker,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val redisProperties: ChatRedisProperties,
) {
    private val logger = LoggerFactory.getLogger(WebSocketSessionManager::class.java)

    private val userSession = ConcurrentHashMap<Long, MutableSet<WebSocketSession>>()

    @PostConstruct
    fun initialize() {
        redisMessageBroker.setLocalMessageHandler { roomId, msg ->
            sendMessageToLocalRoom(roomId, msg)
        }
    }

    fun addSession(userId: Long, session: WebSocketSession) {
        logger.info("Adding session $userId to server")
        userSession.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun removeSession(userId: Long, session: WebSocketSession) {
        userSession[userId]?.remove(session)

        if (userSession[userId]?.isEmpty() == true) {
            userSession.remove(userId)

            val totalConnectedUsers = userSession.values.sumOf { sessions ->
                sessions.count { it.isOpen }
            }

            if (totalConnectedUsers == 0) {
                val serverId = redisMessageBroker.getServerId()
                val serverRoomKey = serverRoomKey(serverId)

                val subscribedRooms = redisTemplate.opsForSet().members(serverRoomKey) ?: emptySet()

                subscribedRooms.forEach { roomIdStr ->
                    val roomId = roomIdStr.toLongOrNull()
                    if (roomId != null) {
                        redisMessageBroker.unsubscribeFromRoom(roomId)
                    }
                }

                redisTemplate.delete(serverRoomKey)
                logger.info("Removed $totalConnectedUsers $subscribedRooms")
            }
        }
    }

    fun joinRoom(userId: Long, roomId: Long) {
        val serverId = redisMessageBroker.getServerId()

        val serverRoomKey = serverRoomKey(serverId)

        val wasAlreadySubscribed = redisTemplate.opsForSet().isMember(serverRoomKey, roomId.toString()) == true

        if (!wasAlreadySubscribed) {
            redisMessageBroker.subscribeToRoom(roomId)
        }

        redisTemplate.opsForSet().add(serverRoomKey, roomId.toString())

        logger.info("Joined $roomId for $userId $serverId to server $serverRoomKey")
    }

    fun sendMessageToLocalRoom(roomId: Long, message: ChatMessage, excludeUserId: Long? = null) {
        val json = objectMapper.writeValueAsString(message)

        // 채팅방을 확인을 하면서, 관련된 방에 메시지를 전송
        userSession.forEach { (userId, session) ->
            if (userId != excludeUserId) {
                val isMember = chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)

                if (isMember) {
                    val closedSessions = mutableSetOf<WebSocketSession>()

                    session.forEach { s ->
                        if (s.isOpen) {
                            try {
                                s.sendMessage(TextMessage(json))
                                logger.info("Sending message to local room $roomId")
                            } catch (e: Exception) {
                                logger.error(e.message, e)
                                closedSessions.add(s)
                            }
                        } else {
                            closedSessions.add(s)
                        }
                    }

                    if (closedSessions.isNotEmpty()) {
                        session.removeAll(closedSessions)
                    }
                } else {
                    logger.debug("not member of $roomId for $userId")
                }
            }
        }
    }

    fun isUserOnlineLocally(userId: Long): Boolean {
        val sessions = userSession[userId] ?: return false

        val openSession = sessions.filter { it.isOpen }

        if (openSession.size != sessions.size) {
            val closedSessions = sessions.filter { !it.isOpen }
            sessions.removeAll(closedSessions)

            if (sessions.isEmpty()) {
                userSession.remove(userId)
            }
        }

        return openSession.isNotEmpty()
    }

    private fun serverRoomKey(serverId: String): String = "${redisProperties.serverRoomsKeyPrefix}$serverId"
}
