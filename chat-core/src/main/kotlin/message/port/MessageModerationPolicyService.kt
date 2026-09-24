package com.chat.core.message.port

import com.chat.domain.model.MessageType

interface MessageModerationPolicyService {
    fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType)

    object Noop : MessageModerationPolicyService {
        override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) = Unit
    }
}
