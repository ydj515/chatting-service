package com.chat.websocket.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chat.auth")
data class HandshakeAuthProperties(
    val session: Session = Session(),
    val webSocketTicket: WebSocketTicket = WebSocketTicket(),
) {
    data class Session(val tokenQueryParam: String = "token")

    data class WebSocketTicket(val ticketQueryParam: String = "ticket", val sessionFallbackEnabled: Boolean = true)
}
