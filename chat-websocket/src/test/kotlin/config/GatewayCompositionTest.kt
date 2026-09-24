package com.chat.websocket.config

import com.chat.core.auth.service.WebSocketTicketSessionPolicy
import com.chat.core.gateway.port.GatewayMemberships
import com.chat.core.gateway.port.GatewayRoomTransport
import com.chat.core.gateway.port.LocalGateway
import com.chat.core.gateway.port.SessionControlEvents
import com.chat.websocket.service.WebSocketRoomSubscriptions
import com.chat.websocket.service.WebSocketSessionAuthorization
import com.chat.websocket.service.WebSocketSessionManager
import com.chat.websocket.service.WebSocketSessionTransport
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class GatewayCompositionTest {
    @Test
    fun `gateway composes with core ports and no persistence implementation`() {
        AnnotationConfigApplicationContext().use { context ->
            val controls = mock(SessionControlEvents::class.java)
            context.beanFactory.registerSingleton("controls", controls)
            context.beanFactory.registerSingleton("members", mock(GatewayMemberships::class.java))
            context.beanFactory.registerSingleton("rooms", mock(GatewayRoomTransport::class.java))
            context.beanFactory.registerSingleton("policy", mock(WebSocketTicketSessionPolicy::class.java))
            context.beanFactory.registerSingleton("mapper", ObjectMapper())
            context.beanFactory.registerSingleton("properties", ChatWebSocketGatewayProperties())
            context.register(
                WebSocketGatewayConfig::class.java, WebSocketSessionAuthorization::class.java,
                WebSocketSessionTransport::class.java, WebSocketRoomSubscriptions::class.java, WebSocketSessionManager::class.java,
            )
            context.refresh()
            assertSame(context.getBean(WebSocketSessionManager::class.java), context.getBean(LocalGateway::class.java))
            assertTrue(mockingDetails(controls).invocations.any { it.method.name == "setLocalForceLogoutHandler" })
        }
    }
}
