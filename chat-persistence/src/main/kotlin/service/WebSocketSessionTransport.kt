package com.chat.persistence.service

import com.chat.persistence.config.ChatWebSocketGatewayProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.nio.ByteBuffer
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

@Service
class WebSocketSessionTransport(
    private val gatewayProperties: ChatWebSocketGatewayProperties,
    private val authorization: WebSocketSessionAuthorization,
    @Qualifier("webSocketOutboundExecutor") private val outboundExecutor: Executor,
    private val gatewayMetrics: WebSocketGatewayMetrics = WebSocketGatewayMetrics.Noop,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(WebSocketSessionTransport::class.java)

    fun nowMillis(): Long = clock.millis()

    fun registerGauges(connectionCount: () -> Int, roomSubscriptionCount: () -> Int, sendQueueDepth: () -> Int) {
        gatewayMetrics.registerGauges(connectionCount, roomSubscriptionCount, sendQueueDepth)
    }

    fun recordDelivery(bytes: Long) {
        gatewayMetrics.recordLocalDelivery(1)
        gatewayMetrics.recordOutboundBytes(bytes)
    }

    fun recordBatchFrame() {
        gatewayMetrics.recordBatchFrame()
    }

    internal fun create(userId: Long, session: WebSocketSession, onRemove: (Long, WebSocketSession) -> Unit): ManagedWebSocketSession {
        val outboundSession = ConcurrentWebSocketSessionDecorator(
            session,
            gatewayProperties.outboundSendTimeLimitMillis,
            gatewayProperties.outboundSendBufferSizeLimitBytes,
        )
        val nowMillis = clock.millis()

        return ManagedWebSocketSession(
            userId = userId,
            session = outboundSession,
            roomIds = ConcurrentHashMap.newKeySet(),
            lastActivityAtMillis = AtomicLong(nowMillis),
            lastHeartbeatSentAtMillis = AtomicLong(nowMillis),
            outboundQueue = BoundedOutboundSessionQueue(
                maxPendingMessages = gatewayProperties.outboundQueueMaxPendingMessages,
                executor = outboundExecutor,
                sender = { payload ->
                    val startNanos = System.nanoTime()
                    try {
                        check(!rejectUnauthorizedSession(outboundSession)) { "WebSocket session authorization expired or revoked" }
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
                    onRemove(userId, session)
                },
                onFailure = { throwable ->
                    logger.error("Failed to send WebSocket message to ${session.id}", throwable)
                    onRemove(userId, session)
                },
            ),
        )
    }

    internal fun pollHeartbeats(sessions: Collection<ManagedWebSocketSession>, nowMillis: Long, onRemove: (Long, WebSocketSession) -> Unit) {
        sessions.forEach { sessionRef ->
            val session = sessionRef.session
            if (rejectUnauthorizedSession(session) || !session.isOpen) {
                onRemove(sessionRef.userId, session)
                return@forEach
            }

            if (!gatewayProperties.heartbeatEnabled) return@forEach

            if (nowMillis - sessionRef.lastActivityAtMillis.get() > gatewayProperties.heartbeatTimeoutMillis) {
                logger.warn("Closing session ${session.id} because heartbeat timed out")
                closeSession(session, HEARTBEAT_TIMEOUT_STATUS)
                onRemove(sessionRef.userId, session)
                return@forEach
            }

            if (nowMillis - sessionRef.lastHeartbeatSentAtMillis.get() >= gatewayProperties.heartbeatIntervalMillis) {
                sendHeartbeat(sessionRef, nowMillis, onRemove)
            }
        }
    }

    private fun sendHeartbeat(sessionRef: ManagedWebSocketSession, nowMillis: Long, onRemove: (Long, WebSocketSession) -> Unit) {
        try {
            sessionRef.session.sendMessage(PingMessage(ByteBuffer.allocate(0)))
            sessionRef.lastHeartbeatSentAtMillis.set(nowMillis)
        } catch (e: Exception) {
            logger.debug("Failed to send heartbeat ping to WebSocket session ${sessionRef.session.id}", e)
            closeSession(sessionRef.session, HEARTBEAT_TIMEOUT_STATUS)
            onRemove(sessionRef.userId, sessionRef.session)
        }
    }

    fun rejectUnauthorizedSession(session: WebSocketSession): Boolean {
        if (!authorization.isInvalid(session)) return false
        closeSession(session, CloseStatus(4003, "Session expired or revoked"))
        return true
    }

    fun closeSession(session: WebSocketSession, closeStatus: CloseStatus) {
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
        val HEARTBEAT_TIMEOUT_STATUS = CloseStatus(4004, "Heartbeat timeout")
    }
}

internal data class ManagedWebSocketSession(
    val userId: Long,
    val session: WebSocketSession,
    val roomIds: MutableSet<Long>,
    val lastActivityAtMillis: AtomicLong,
    val lastHeartbeatSentAtMillis: AtomicLong,
    val outboundQueue: BoundedOutboundSessionQueue,
)
