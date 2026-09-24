package com.chat.core.gateway.port

interface SessionControlEvents {
    fun setLocalForceLogoutHandler(handler: (Long, String) -> Unit)
}
