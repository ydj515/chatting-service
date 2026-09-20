package com.chat.persistence.service

import com.chat.domain.dto.ChatMessageBatch
import com.chat.domain.dto.WebSocketMessage
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

@Service
class WebSocketSessionManager(
    private val objectMapper: ObjectMapper,
    private val redisMessageBroker: RedisMessageBroker,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val roomSubscriptions: WebSocketRoomSubscriptions,
    private val transport: WebSocketSessionTransport,
    private val sessionControlBroker: RedisSessionControlBroker? = null,
) {
    private val logger = LoggerFactory.getLogger(WebSocketSessionManager::class.java)

    private val sessionsById = ConcurrentHashMap<String, ManagedWebSocketSession>()
    private val sessionIdsByUserId = ConcurrentHashMap<Long, MutableSet<String>>()

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
        sessionControlBroker?.setLocalForceLogoutHandler { userId, _ ->
            closeSessionsForUser(userId)
        }

        transport.registerGauges(
            connectionCount = { sessionsById.size },
            // 방 개수가 아니라 (room, session) 구독 쌍 합계로 실제 구독 부하를 센다.
            roomSubscriptionCount = { roomSubscriptions.subscriptionCount() },
            sendQueueDepth = { currentSendQueueDepth() },
        )
    }

    // RoomPolicy OVERLOAD 판정 입력으로 노출하는 현재 Gateway pending depth 합계.
    fun currentSendQueueDepth(): Int =
        sessionsById.values.sumOf { it.outboundQueue.pendingSize() }

    fun addSession(userId: Long, session: WebSocketSession) {
        logger.info("Adding session $userId to server")
        sessionsById[session.id]?.let { existing ->
            removeSession(existing.userId, existing.session)
        }
        val sessionRef = transport.create(userId, session, ::removeSession)
        sessionsById[session.id] = sessionRef
        sessionIdsByUserId.compute(userId) { _, sessionIds ->
            val nextSessionIds = sessionIds ?: ConcurrentHashMap.newKeySet()
            nextSessionIds.add(session.id)
            nextSessionIds
        }
    }

    fun rejectUnauthorizedSession(session: WebSocketSession): Boolean {
        if (!transport.rejectUnauthorizedSession(session)) return false
        sessionsById[session.id]?.let { removeSession(it.userId, session) }
        return true
    }

    fun recordSessionActivity(session: WebSocketSession) = recordSessionActivity(session, transport.nowMillis())

    fun recordSessionActivity(session: WebSocketSession, nowMillis: Long) {
        sessionsById[session.id]?.lastActivityAtMillis?.set(nowMillis)
    }

    fun pollHeartbeats() = pollHeartbeats(transport.nowMillis())

    fun pollHeartbeats(nowMillis: Long) {
        transport.pollHeartbeats(sessionsById.values, nowMillis, ::removeSession)
        roomSubscriptions.retryIndexUpdates()
    }

    fun removeSession(userId: Long, session: WebSocketSession) {
        val sessionRef = sessionsById.remove(session.id) ?: return
        sessionRef.outboundQueue.close()
        removeSessionIdFromUser(sessionRef.userId, session.id)

        sessionRef.roomIds.toList().forEach { roomId ->
            removeSessionFromRoom(sessionRef, roomId)
        }
    }

    fun joinRoom(userId: Long, roomId: Long) {
        val sessionRefs = openManagedWebSocketSessionsForUser(userId)
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
        openManagedWebSocketSessionsForUser(userId).forEach { sessionRef ->
            removeSessionFromRoom(sessionRef, roomId)
        }
    }

    fun sendMessageToLocalRoom(roomId: Long, message: WebSocketMessage, excludeUserId: Long? = null) {
        val json = objectMapper.writerFor(com.chat.domain.dto.WebSocketMessage::class.java).writeValueAsString(message)
        val sessionIds = roomSubscriptions.sessionIds(roomId) ?: return
        val userIds = sessionIds.mapNotNull { sessionsById[it]?.userId }.distinct()
        if (userIds.isEmpty()) return
        // Use the primary membership store, never the pub/sub index, as delivery authorization.
        // A lookup failure propagates before anything is enqueued (fail closed).
        val activeUsers = chatRoomMemberRepository.findActiveUserIds(roomId, userIds).toSet()
        (userIds - activeUsers).forEach { leaveRoom(it, roomId) }
        // payload 크기는 루프 내내 동일하므로 1회만 계산해 세션 수만큼의 byte array 할당을 피한다.
        val outboundBytes = json.toByteArray(Charsets.UTF_8).size.toLong()

        sessionIds.forEach { sessionId ->
            val sessionRef = sessionsById[sessionId] ?: return@forEach
            if (sessionRef.userId !in activeUsers || sessionRef.userId == excludeUserId) {
                return@forEach
            }

            val session = sessionRef.session
            if (!session.isOpen) {
                removeSession(sessionRef.userId, session)
                return@forEach
            }

            if (sessionRef.outboundQueue.enqueue(json)) {
                transport.recordDelivery(outboundBytes)
            }
        }
        if (message is ChatMessageBatch) {
            transport.recordBatchFrame()
        }
    }

    fun sendTextToSession(session: WebSocketSession, payload: String, priority: Boolean = false): Boolean {
        val sessionRef = sessionsById[session.id] ?: return false
        val outboundSession = sessionRef.session
        if (!outboundSession.isOpen) {
            removeSession(sessionRef.userId, outboundSession)
            return false
        }

        return sessionRef.outboundQueue.enqueue(payload, priority = priority)
    }

    fun isUserOnlineLocally(userId: Long): Boolean = openManagedWebSocketSessionsForUser(userId).isNotEmpty()

    fun closeSessionsForUser(userId: Long, closeStatus: CloseStatus = SESSION_REVOKED_STATUS) {
        openManagedWebSocketSessionsForUser(userId).forEach { sessionRef ->
            transport.closeSession(sessionRef.session, closeStatus)
            removeSession(sessionRef.userId, sessionRef.session)
        }
    }

    private fun openManagedWebSocketSessionsForUser(userId: Long): List<ManagedWebSocketSession> {
        val sessionIds = sessionIdsByUserId[userId]?.toList() ?: return emptyList()
        val sessionRefs = mutableListOf<ManagedWebSocketSession>()

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

    private fun addSessionToRoom(sessionRef: ManagedWebSocketSession, roomId: Long) {
        if (sessionRef.roomIds.add(roomId)) {
            roomSubscriptions.addSessionIdToRoom(roomId, sessionRef.session.id)

            logger.info("Joined $roomId for ${sessionRef.userId} ${redisMessageBroker.getServerId()}")
        }
    }

    private fun removeSessionFromRoom(sessionRef: ManagedWebSocketSession, roomId: Long) {
        if (!sessionRef.roomIds.remove(roomId)) {
            return
        }

        roomSubscriptions.removeSessionIdFromRoom(roomId, sessionRef.session.id)
    }

    private fun removeSessionIdFromUser(userId: Long, sessionId: String) {
        sessionIdsByUserId.computeIfPresent(userId) { _, sessionIds ->
            sessionIds.remove(sessionId)
            if (sessionIds.isEmpty()) null else sessionIds
        }
    }

    private companion object {
        val SESSION_REVOKED_STATUS = CloseStatus(4003, "Session revoked")
    }
}
