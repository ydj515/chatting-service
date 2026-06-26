package com.chat.persistence.service

import com.chat.domain.service.SessionControlPublisher
import com.chat.persistence.config.ChatAuthProperties
import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class RedisSessionControlBroker(
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageListenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper,
    private val authProperties: ChatAuthProperties,
    redisProperties: ChatRedisProperties,
) : SessionControlPublisher, MessageListener {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val serverId = redisProperties.broker.serverId
        ?.takeIf { it.isNotBlank() }
        ?: "server-${System.currentTimeMillis()}"
    private var localForceLogoutHandler: ((Long, String) -> Unit)? = null

    @PostConstruct
    fun initialize() {
        messageListenerContainer.addMessageListener(this, ChannelTopic(authProperties.session.controlTopic))
        logger.info("Subscribed to session control topic ${authProperties.session.controlTopic}")
    }

    @PreDestroy
    fun cleanup() {
        messageListenerContainer.removeMessageListener(this, ChannelTopic(authProperties.session.controlTopic))
    }

    fun setLocalForceLogoutHandler(handler: (Long, String) -> Unit) {
        localForceLogoutHandler = handler
    }

    override fun forceLogoutUser(userId: Long, reason: String) {
        val event = SessionControlEvent(
            id = "$serverId-session-control-${System.currentTimeMillis()}-${System.nanoTime()}",
            serverId = serverId,
            type = EventType.FORCE_LOGOUT_USER,
            userId = userId,
            reason = reason,
            timestamp = LocalDateTime.now(),
        )
        redisTemplate.convertAndSend(authProperties.session.controlTopic, objectMapper.writeValueAsString(event))
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val event = objectMapper.readValue(message.body, SessionControlEvent::class.java)
            if (event.serverId == serverId) {
                return
            }
            when (event.type) {
                EventType.FORCE_LOGOUT_USER -> localForceLogoutHandler?.invoke(event.userId, event.reason)
            }
        } catch (e: Exception) {
            logger.warn("Failed to process session control event", e)
        }
    }

    data class SessionControlEvent(
        val id: String,
        val serverId: String,
        val type: EventType,
        val userId: Long,
        val reason: String,
        val timestamp: LocalDateTime,
    )

    enum class EventType {
        FORCE_LOGOUT_USER,
    }
}
