package com.chat.persistence.redis

import com.chat.domain.dto.ChatMessage
import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.annotation.JsonAlias
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
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageBroker(
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageListenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper,
    private val redisProperties: ChatRedisProperties,
) : MessageListener {
    private val logger = LoggerFactory.getLogger(RedisMessageBroker::class.java)
    private val serverId = redisProperties.broker.serverId
        ?.takeIf { it.isNotBlank() }
        ?: "server-${System.currentTimeMillis()}"
    private val processedMessages = ConcurrentHashMap<String, Long>()
    private val subscribeRooms = ConcurrentHashMap.newKeySet<Long>()
    private var localMessageHandler: ((Long, ChatMessage) -> Unit)? = null
    private var localMembershipHandler: ((DistributedMembershipEvent) -> Unit)? = null

    fun getServerId() = serverId

    @PostConstruct
    fun initialize() {
        logger.info("Initializing RedisMessageListenerContainer")
        messageListenerContainer.addMessageListener(this, ChannelTopic(redisProperties.membershipTopic))
        logger.info("Subscribed to membership topic ${redisProperties.membershipTopic}")

        Thread {
            try {
                Thread.sleep(redisProperties.broker.cleanupInitialDelay.toMillis())
                cleanUpProcessedMessages()
            } catch (e: Exception) {
                logger.error("Error in initializing RedisMessageListenerContainer", e)
            }
        }.apply {
            isDaemon = true
            name = "redis-broker-cleanup"
            start()
        }
    }

    @PreDestroy
    fun cleanup() {
        messageListenerContainer.removeMessageListener(this, ChannelTopic(redisProperties.membershipTopic))
        subscribeRooms.forEach { roomId ->
            unsubscribeFromRoom(roomId)
        }
        logger.info("Removing RedisMessageListenerContainer")
    }

    fun setLocalMessageHandler(handler: (Long, ChatMessage) -> Unit) {
        this.localMessageHandler = handler
    }

    fun setLocalMembershipHandler(handler: (DistributedMembershipEvent) -> Unit) {
        this.localMembershipHandler = handler
    }

    fun subscribeToRoom(roomId: Long) {
        if (subscribeRooms.add(roomId)) {
            val topic = ChannelTopic(roomTopic(roomId))
            messageListenerContainer.addMessageListener(this, topic)
            logger.info("Subscribed to $roomId")
        } else {
            logger.error("Room $roomId does not exist")
        }
    }

    fun unsubscribeFromRoom(roomId: Long) {
        if (subscribeRooms.remove(roomId)) {
            val topic = ChannelTopic(roomTopic(roomId))
            messageListenerContainer.removeMessageListener(this, topic)
            logger.info("Unsubscribed from $roomId")
        } else {
            logger.error("Room $roomId does not exist")
        }
    }

    fun publishMembershipChanged(userId: Long, roomId: Long, action: MembershipAction) {
        try {
            val event = DistributedMembershipEvent(
                id = "$serverId-membership-${System.currentTimeMillis()}-${System.nanoTime()}",
                serverId = serverId,
                userId = userId,
                roomId = roomId,
                action = action,
                timestamp = LocalDateTime.now(),
            )

            val json = objectMapper.writeValueAsString(event)
            redisTemplate.convertAndSend(redisProperties.membershipTopic, json)

            logger.info("Published membership event $json")
        } catch (e: Exception) {
            logger.error("Error publishing membership event for room $roomId and user $userId", e)
        }
    }

    fun broadcastToRoom(roomId: Long, message: ChatMessage, excludeServerId: String? = null) {
        try {
            val distributedMessage = DistributedMessage(
                id = "$serverId-${System.currentTimeMillis()}-${System.nanoTime()}",
                serverId = serverId,
                roomId = roomId,
                excludeServerId = excludeServerId,
                timestamp = LocalDateTime.now(),
                payload = message,
            )

            val json = objectMapper.writeValueAsString(distributedMessage)
            redisTemplate.convertAndSend(roomTopic(roomId), json)

            logger.info("Broadcast to $roomId to $json")
        } catch (e: Exception) {
            logger.error("Error broadcast to $roomId", e)
        }
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val json = String(message.body)
            val channel = String(message.channel)

            if (channel == redisProperties.membershipTopic) {
                handleMembershipMessage(json)
                return
            }

            val distributedMessage = objectMapper.readValue(json, DistributedMessage::class.java)

            if (distributedMessage.excludeServerId == serverId) {
                logger.error("excludeServerId to $serverId")
                return
            }

            if (processedMessages.containsKey(distributedMessage.id)) {
                logger.error("processedMessages $distributedMessage")
                return
            }

            localMessageHandler?.invoke(distributedMessage.roomId, distributedMessage.payload)

            processedMessages[distributedMessage.id] = System.currentTimeMillis()

            val maxProcessedMessageSize = redisProperties.broker.processedMessageMaxSize.coerceAtLeast(1)
            if (processedMessages.size > maxProcessedMessageSize) {
                val oldestEntries = processedMessages.entries.sortedBy { it.value }
                    .take(processedMessages.size - maxProcessedMessageSize)

                oldestEntries.forEach { processedMessages.remove(it.key) }
            }

            logger.info("processedMessages $distributedMessage.id")

        } catch (e: Exception) {
            logger.error("Error in on message", e)
        }
    }

    private fun handleMembershipMessage(json: String) {
        val event = objectMapper.readValue(json, DistributedMembershipEvent::class.java)

        if (event.serverId == serverId) {
            logger.debug("Ignoring local membership event ${event.id}")
            return
        }

        localMembershipHandler?.invoke(event)
        logger.info("Processed membership event ${event.id}")
    }

    private fun cleanUpProcessedMessages() {
        val now = System.currentTimeMillis()
        val ttlMillis = redisProperties.broker.processedMessageTtl.toMillis()
        val expiredKeys = processedMessages.filter { (_, time) ->
            now - time > ttlMillis
        }.keys

        expiredKeys.forEach { processedMessages.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            logger.info("Removed ${processedMessages.size} messages from Redis")
        }
    }

    private fun roomTopic(roomId: Long): String = "${redisProperties.roomTopicPrefix}$roomId"

    data class DistributedMessage(
        val id: String,
        val serverId: String,
        val roomId: Long,
        @JsonAlias("excludeSeverId")
        val excludeServerId: String?,
        val timestamp: LocalDateTime,
        val payload: ChatMessage,
    )

    data class DistributedMembershipEvent(
        val id: String,
        val serverId: String,
        val userId: Long,
        val roomId: Long,
        val action: MembershipAction,
        val timestamp: LocalDateTime,
    )

    enum class MembershipAction {
        JOIN,
        LEAVE,
    }
}
