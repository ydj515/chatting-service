package com.chat.persistence.service

import com.chat.core.room.port.MembershipEvents
import com.chat.persistence.redis.RedisMessageBroker
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class MembershipEventPublisher(
    private val redisMessageBroker: RedisMessageBroker,
    private val webSocketSessionManager: WebSocketSessionManager,
) : MembershipEvents {
    override fun joinedAfterCommit(userId: Long, roomId: Long) = publishAfterCommit(userId, roomId, RedisMessageBroker.MembershipAction.JOIN)

    override fun leftAfterCommit(userId: Long, roomId: Long) = publishAfterCommit(userId, roomId, RedisMessageBroker.MembershipAction.LEAVE)

    fun publishAfterCommit(
        userId: Long,
        roomId: Long,
        action: RedisMessageBroker.MembershipAction,
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishMembershipChanged(userId, roomId, action)
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                publishMembershipChanged(userId, roomId, action)
            }
        })
    }

    private fun publishMembershipChanged(
        userId: Long,
        roomId: Long,
        action: RedisMessageBroker.MembershipAction,
    ) {
        when (action) {
            RedisMessageBroker.MembershipAction.JOIN -> {
                if (webSocketSessionManager.isUserOnlineLocally(userId)) {
                    webSocketSessionManager.joinRoom(userId, roomId)
                }
            }
            RedisMessageBroker.MembershipAction.LEAVE -> webSocketSessionManager.leaveRoom(userId, roomId)
        }

        redisMessageBroker.publishMembershipChanged(
            userId = userId,
            roomId = roomId,
            action = action,
        )
    }
}
