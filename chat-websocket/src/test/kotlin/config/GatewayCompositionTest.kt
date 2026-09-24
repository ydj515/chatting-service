package com.chat.websocket.config

import com.chat.core.auth.service.WebSocketTicketSessionPolicy
import com.chat.core.gateway.port.GatewayMemberships
import com.chat.core.gateway.port.LocalGateway
import com.chat.core.gateway.port.SessionControlEvents
import com.chat.protocol.gateway.GatewayRoomTransport
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
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ExecutorService

class GatewayCompositionTest {
    @Test
    fun `gateway composes with core ports and no persistence implementation`() {
        lateinit var executor: ExecutorService
        val socket = mock(WebSocketSession::class.java)
        `when`(socket.id).thenReturn("connection")
        `when`(socket.isOpen).thenReturn(true)
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
            val manager = context.getBean(WebSocketSessionManager::class.java)
            executor = context.getBean("webSocketOutboundExecutor", ExecutorService::class.java)
            manager.addSession(7, socket)
            assertSame(manager, context.getBean(LocalGateway::class.java))
            assertTrue(mockingDetails(controls).invocations.any { it.method.name == "setLocalForceLogoutHandler" })
        }
        verify(socket).close(CloseStatus.GOING_AWAY)
        assertTrue(executor.isShutdown)
    }
}
