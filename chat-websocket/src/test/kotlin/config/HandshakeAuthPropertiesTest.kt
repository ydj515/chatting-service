package com.chat.websocket.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class HandshakeAuthPropertiesTest {
    @Test
    fun `delivery settings bind existing auth keys without token signing configuration`() {
        val binder = Binder(
            MapConfigurationPropertySource(
                mapOf(
                    "chat.auth.session.token-query-param" to "access",
                    "chat.auth.web-socket-ticket.ticket-query-param" to "once",
                    "chat.auth.web-socket-ticket.session-fallback-enabled" to "false",
                ),
            ),
        )
        val settings = binder.bind("chat.auth", Bindable.of(HandshakeAuthProperties::class.java)).get()
        assertEquals("access", settings.session.tokenQueryParam)
        assertEquals("once", settings.webSocketTicket.ticketQueryParam)
        assertFalse(settings.webSocketTicket.sessionFallbackEnabled)
    }
}
