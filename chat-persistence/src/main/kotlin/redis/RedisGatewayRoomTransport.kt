package com.chat.persistence.redis

import com.chat.core.dto.WebSocketMessage
import com.chat.core.gateway.port.GatewayRoomTransport
import com.chat.core.gateway.port.MembershipAction
import com.chat.core.gateway.port.MembershipChange
import com.chat.persistence.config.ChatRedisProperties
import org.slf4j.LoggerFactory
import org.springframework.core.NestedRuntimeException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisGatewayRoomTransport(
    private val redis: RedisTemplate<String, String>,
    private val properties: ChatRedisProperties,
    private val broker: RedisMessageBroker,
) : GatewayRoomTransport {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getServerId(): String = broker.getServerId()

    override fun setLocalMessageHandler(handler: (Long, WebSocketMessage) -> Unit) = broker.setLocalMessageHandler(handler)

    override fun setLocalMembershipHandler(handler: (MembershipChange) -> Unit) = broker.setLocalMembershipHandler { event ->
        handler(MembershipChange(event.userId, event.roomId, MembershipAction.valueOf(event.action.name)))
    }

    override fun subscribeToRoom(roomId: Long) = broker.subscribeToRoom(roomId)

    override fun unsubscribeFromRoom(roomId: Long) = broker.unsubscribeFromRoom(roomId)

    override fun synchronizeServerRoom(roomId: Long, active: Boolean): Boolean = try {
        val key = "${properties.serverRoomsKeyPrefix}${broker.getServerId()}"
        if (active) redis.opsForSet().add(key, roomId.toString()) else redis.opsForSet().remove(key, roomId.toString())
        true
    } catch (failure: NestedRuntimeException) {
        logger.warn("Unable to synchronize server room index for {}", roomId, failure)
        false
    }
}
