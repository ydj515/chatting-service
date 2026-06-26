package com.chat.domain.service

interface SessionControlPublisher {
    fun forceLogoutUser(userId: Long, reason: String)

    object Noop : SessionControlPublisher {
        override fun forceLogoutUser(userId: Long, reason: String) = Unit
    }
}
