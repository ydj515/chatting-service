package com.chat.core.message.port

interface UserSanctionPolicyService {
    fun requireAllowedToSend(roomId: Long, userId: Long)

    object Noop : UserSanctionPolicyService {
        override fun requireAllowedToSend(roomId: Long, userId: Long) = Unit
    }
}
