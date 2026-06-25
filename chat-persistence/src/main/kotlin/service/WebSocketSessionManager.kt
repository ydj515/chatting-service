package com.chat.persistence.service

import com.chat.domain.dto.ChatMessageBatch
import com.chat.domain.dto.WebSocketMessage
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
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

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
    private val gatewayMetrics: WebSocketGatewayMetrics = WebSocketGatewayMetrics.Noop,
) {
    private val logger = LoggerFactory.getLogger(WebSocketSessionManager::class.java)

    private val sessionsById = ConcurrentHashMap<String, SessionRef>()
    private val sessionIdsByUserId = ConcurrentHashMap<Long, MutableSet<String>>()
    private val sessionIdsByRoomId = ConcurrentHashMap<Long, MutableSet<String>>()

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

        gatewayMetrics.registerGauges(
            connectionCount = { sessionsById.size },
            roomSubscriptionCount = { sessionIdsByRoomId.size },
            sendQueueDepth = { totalPendingSize() },
        )
    }

    // RoomPolicy OVERLOAD 판정 입력으로 노출하는 현재 Gateway pending depth 합계.
    fun currentSendQueueDepth(): Int = totalPendingSize()

    private fun totalPendingSize(): Int =
        sessionsById.values.sumOf { it.outboundQueue.pendingSize() }

    fun addSession(userId: Long, session: WebSocketSession) {
        logger.info("Adding session $userId to server")
        sessionsById[session.id]?.let { existing ->
            removeSession(existing.userId, existing.session)
        }
        val outboundSession = ConcurrentWebSocketSessionDecorator(
            session,
            gatewayProperties.outboundSendTimeLimitMillis,
            gatewayProperties.outboundSendBufferSizeLimitBytes,
        )

        val sessionRef = SessionRef(
            userId = userId,
            session = outboundSession,
            roomIds = ConcurrentHashMap.newKeySet(),
            outboundQueue = BoundedOutboundSessionQueue(
                maxPendingMessages = gatewayProperties.outboundQueueMaxPendingMessages,
                executor = outboundExecutor,
                sender = { payload ->
                    val startNanos = System.nanoTime()
                    try {
                        outboundSession.sendMessage(TextMessage(payload))
                        gatewayMetrics.recordWriteLatency(System.nanoTime() - startNanos, "success")
                    } catch (t: Throwable) {
                        gatewayMetrics.recordWriteLatency(System.nanoTime() - startNanos, "failure")
                        throw t
                    }
                },
                onOverflow = {
                    logger.warn("Closing session ${session.id} because outbound queue is full")
                    gatewayMetrics.recordSlowClientDisconnect()
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
        addSessionIdToUser(userId, session.id)
    }

    fun removeSession(userId: Long, session: WebSocketSession) {
        val sessionRef = sessionsById.remove(session.id) ?: return
        sessionRef.outboundQueue.close()
        removeSessionIdFromUser(sessionRef.userId, session.id)

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

    fun sendMessageToLocalRoom(roomId: Long, message: WebSocketMessage, excludeUserId: Long? = null) {
        val json = objectMapper.writerFor(com.chat.domain.dto.WebSocketMessage::class.java).writeValueAsString(message)
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
                gatewayMetrics.recordLocalDelivery(1)
                gatewayMetrics.recordOutboundBytes(json.toByteArray(Charsets.UTF_8).size.toLong())
            }
        }
        if (message is ChatMessageBatch) {
            gatewayMetrics.recordBatchFrame()
        }
    }

    fun sendTextToSession(session: WebSocketSession, payload: String): Boolean {
        val sessionRef = sessionsById[session.id] ?: return false
        val outboundSession = sessionRef.session
        if (!outboundSession.isOpen) {
            removeSession(sessionRef.userId, outboundSession)
            return false
        }

        return sessionRef.outboundQueue.enqueue(payload)
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
                removeSessionIdFromUser(userId, sessionId)
            } else if (sessionRef.session.isOpen) {
                sessionRefs.add(sessionRef)
            } else {
                removeSession(sessionRef.userId, sessionRef.session)
            }
        }

        return sessionRefs
    }

    private fun addSessionToRoom(sessionRef: SessionRef, roomId: Long) {
        if (sessionRef.roomIds.add(roomId)) {
            addSessionIdToRoom(roomId, sessionRef.session.id)

            logger.info("Joined $roomId for ${sessionRef.userId} ${redisMessageBroker.getServerId()}")
        }
    }

    private fun removeSessionFromRoom(sessionRef: SessionRef, roomId: Long) {
        if (!sessionRef.roomIds.remove(roomId)) {
            return
        }

        removeSessionIdFromRoom(roomId, sessionRef.session.id)
    }

    private fun addSessionIdToUser(userId: Long, sessionId: String) {
        sessionIdsByUserId.compute(userId) { _, sessionIds ->
            val nextSessionIds = sessionIds ?: ConcurrentHashMap.newKeySet()
            nextSessionIds.add(sessionId)
            nextSessionIds
        }
    }

    private fun removeSessionIdFromUser(userId: Long, sessionId: String) {
        sessionIdsByUserId.computeIfPresent(userId) { _, sessionIds ->
            sessionIds.remove(sessionId)
            if (sessionIds.isEmpty()) null else sessionIds
        }
    }

    private fun addSessionIdToRoom(roomId: Long, sessionId: String) {
        sessionIdsByRoomId.compute(roomId) { _, sessionIds ->
            val nextSessionIds = sessionIds ?: ConcurrentHashMap.newKeySet()
            val wasEmpty = nextSessionIds.isEmpty()
            nextSessionIds.add(sessionId)
            if (wasEmpty) {
                redisMessageBroker.subscribeToRoom(roomId)
                redisTemplate.opsForSet().add(serverRoomKey(redisMessageBroker.getServerId()), roomId.toString())
            }
            nextSessionIds
        }
    }

    private fun removeSessionIdFromRoom(roomId: Long, sessionId: String) {
        sessionIdsByRoomId.computeIfPresent(roomId) { _, sessionIds ->
            sessionIds.remove(sessionId)
            if (sessionIds.isEmpty()) {
                redisMessageBroker.unsubscribeFromRoom(roomId)
                redisTemplate.opsForSet().remove(serverRoomKey(redisMessageBroker.getServerId()), roomId.toString())
                null
            } else {
                sessionIds
            }
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
