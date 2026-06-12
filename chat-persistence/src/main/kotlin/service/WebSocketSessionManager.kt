package com.chat.persistence.service

import com.chat.domain.dto.ChatMessage
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Qualifier
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@Service
class WebSocketSessionManager(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val redisMessageBroker: RedisMessageBroker,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val redisProperties: ChatRedisProperties,
    private val gatewayProperties: ChatWebSocketGatewayProperties,
    @Qualifier("webSocketOutboundExecutor")
    private val outboundExecutor: Executor,
) {
    private val logger = LoggerFactory.getLogger(WebSocketSessionManager::class.java)

    private val sessionsById = ConcurrentHashMap<String, SessionRef>()
    private val sessionIdsByUserId = ConcurrentHashMap<Long, MutableSet<String>>()
    private val sessionIdsByRoomId = ConcurrentHashMap<Long, MutableSet<String>>()
    private val localSessionCountByRoomId = ConcurrentHashMap<Long, AtomicInteger>()

    @PostConstruct
    fun initialize() {
        redisMessageBroker.setLocalMessageHandler { roomId, msg ->
            sendMessageToLocalRoom(roomId, msg)
        }
        redisMessageBroker.setLocalMembershipHandler { event ->
            when (event.action) {
                RedisMessageBroker.MembershipAction.JOIN -> {
                    if (isUserOnlineLocally(event.userId)) {
                        joinRoom(event.userId, event.roomId)
                    }
                }
                RedisMessageBroker.MembershipAction.LEAVE -> leaveRoom(event.userId, event.roomId)
            }
        }
    }

    fun addSession(userId: Long, session: WebSocketSession) {
        logger.info("Adding session $userId to server")
        sessionsById[session.id]?.let { existing ->
            removeSession(existing.userId, existing.session)
        }

        val sessionRef = SessionRef(
            userId = userId,
            session = session,
            roomIds = ConcurrentHashMap.newKeySet(),
            outboundQueue = BoundedOutboundSessionQueue(
                maxPendingMessages = gatewayProperties.outboundQueueMaxPendingMessages,
                executor = outboundExecutor,
                sender = { payload -> session.sendMessage(TextMessage(payload)) },
                onOverflow = {
                    logger.warn("Closing session ${session.id} because outbound queue is full")
                    closeSession(session, OUTBOUND_QUEUE_FULL_STATUS)
                    removeSession(userId, session)
                },
                onFailure = { throwable ->
                    logger.error("Failed to send WebSocket message to ${session.id}", throwable)
                    removeSession(userId, session)
                },
            ),
        )
        sessionsById[session.id] = sessionRef
        sessionIdsByUserId.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session.id)
    }

    fun removeSession(userId: Long, session: WebSocketSession) {
        val sessionRef = sessionsById.remove(session.id) ?: return
        sessionRef.outboundQueue.close()
        sessionIdsByUserId[sessionRef.userId]?.remove(session.id)
        if (sessionIdsByUserId[sessionRef.userId]?.isEmpty() == true) {
            sessionIdsByUserId.remove(sessionRef.userId)
        }

        sessionRef.roomIds.toList().forEach { roomId ->
            removeSessionFromRoom(sessionRef, roomId)
        }

        if (sessionsById.isEmpty()) {
            redisTemplate.delete(serverRoomKey(redisMessageBroker.getServerId()))
        }
    }

    fun joinRoom(userId: Long, roomId: Long) {
        val sessionRefs = openSessionRefsForUser(userId)
        if (sessionRefs.isEmpty()) {
            return
        }

        val isMember = chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)
        if (!isMember) {
            logger.debug("not member of $roomId for $userId")
            return
        }

        sessionRefs.forEach { sessionRef ->
            addSessionToRoom(sessionRef, roomId)
        }
    }

    fun leaveRoom(userId: Long, roomId: Long) {
        openSessionRefsForUser(userId).forEach { sessionRef ->
            removeSessionFromRoom(sessionRef, roomId)
        }
    }

    fun sendMessageToLocalRoom(roomId: Long, message: ChatMessage, excludeUserId: Long? = null) {
        val json = objectMapper.writeValueAsString(message)
        val sessionIds = sessionIdsByRoomId[roomId]?.toList() ?: return

        sessionIds.forEach { sessionId ->
            val sessionRef = sessionsById[sessionId] ?: return@forEach
            if (sessionRef.userId == excludeUserId) {
                return@forEach
            }

            val session = sessionRef.session
            if (!session.isOpen) {
                removeSession(sessionRef.userId, session)
                return@forEach
            }

            if (sessionRef.outboundQueue.enqueue(json)) {
                logger.info("Sending message to local room $roomId")
            }
        }
    }

    fun isUserOnlineLocally(userId: Long): Boolean {
        return openSessionRefsForUser(userId).isNotEmpty()
    }

    private fun openSessionRefsForUser(userId: Long): List<SessionRef> {
        val sessionIds = sessionIdsByUserId[userId]?.toList() ?: return emptyList()
        val sessionRefs = mutableListOf<SessionRef>()

        sessionIds.forEach { sessionId ->
            val sessionRef = sessionsById[sessionId]
            if (sessionRef == null) {
                sessionIdsByUserId[userId]?.remove(sessionId)
            } else if (sessionRef.session.isOpen) {
                sessionRefs.add(sessionRef)
            } else {
                removeSession(sessionRef.userId, sessionRef.session)
            }
        }

        if (sessionIdsByUserId[userId]?.isEmpty() == true) {
            sessionIdsByUserId.remove(userId)
        }

        return sessionRefs
    }

    private fun addSessionToRoom(sessionRef: SessionRef, roomId: Long) {
        if (sessionRef.roomIds.add(roomId)) {
            sessionIdsByRoomId.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }.add(sessionRef.session.id)

            val localSessionCount = localSessionCountByRoomId.computeIfAbsent(roomId) { AtomicInteger(0) }
                .incrementAndGet()
            if (localSessionCount == 1) {
                redisMessageBroker.subscribeToRoom(roomId)
                redisTemplate.opsForSet().add(serverRoomKey(redisMessageBroker.getServerId()), roomId.toString())
            }

            logger.info("Joined $roomId for ${sessionRef.userId} ${redisMessageBroker.getServerId()}")
        }
    }

    private fun removeSessionFromRoom(sessionRef: SessionRef, roomId: Long) {
        if (!sessionRef.roomIds.remove(roomId)) {
            return
        }

        sessionIdsByRoomId[roomId]?.remove(sessionRef.session.id)
        if (sessionIdsByRoomId[roomId]?.isEmpty() == true) {
            sessionIdsByRoomId.remove(roomId)
        }

        val localSessionCount = localSessionCountByRoomId[roomId]?.decrementAndGet() ?: 0
        if (localSessionCount <= 0) {
            localSessionCountByRoomId.remove(roomId)
            redisMessageBroker.unsubscribeFromRoom(roomId)
            redisTemplate.opsForSet().remove(serverRoomKey(redisMessageBroker.getServerId()), roomId.toString())
        }
    }

    private data class SessionRef(
        val userId: Long,
        val session: WebSocketSession,
        val roomIds: MutableSet<Long>,
        val outboundQueue: BoundedOutboundSessionQueue,
    )

    private fun serverRoomKey(serverId: String): String {
        return "${redisProperties.serverRoomsKeyPrefix}$serverId"
    }

    private fun closeSession(session: WebSocketSession, closeStatus: CloseStatus) {
        try {
            if (session.isOpen) {
                session.close(closeStatus)
            }
        } catch (e: Exception) {
            logger.debug("Failed to close WebSocket session ${session.id}", e)
        }
    }

    private companion object {
        val OUTBOUND_QUEUE_FULL_STATUS = CloseStatus(1013, "Outbound queue full")
    }
}
