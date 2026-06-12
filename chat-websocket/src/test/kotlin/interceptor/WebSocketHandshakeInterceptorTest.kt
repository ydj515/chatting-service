package com.chat.websocket.interceptor

import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.service.SessionTokenService
import com.chat.persistence.config.ChatAuthProperties
import com.chat.websocket.config.WebSocketProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import java.time.LocalDateTime

class WebSocketHandshakeInterceptorTest {

    private val webSocketProperties = WebSocketProperties(userIdAttribute = "userId")
    private val authProperties = ChatAuthProperties(
        session = ChatAuthProperties.Session(
            secret = "test-secret",
            tokenQueryParam = "token",
        )
    )

    @Test
    fun `핸드셰이크는 token으로 검증한 사용자 ID를 세션 attribute에 저장한다`() {
        val sessionTokenService = mock(SessionTokenService::class.java)
        `when`(sessionTokenService.authenticate("valid-token")).thenReturn(
            AuthenticatedSession(
                userId = 42L,
                expiresAt = LocalDateTime.parse("2026-06-12T12:30:00"),
            )
        )
        val interceptor = WebSocketHandshakeInterceptor(
            webSocketProperties = webSocketProperties,
            sessionTokenService = sessionTokenService,
            authProperties = authProperties,
        )
        val attributes = mutableMapOf<String?, Any?>()

        val accepted = interceptor.beforeHandshake(
            request("token=valid-token&userId=999"),
            response(),
            mock(WebSocketHandler::class.java),
            attributes,
        )

        assertTrue(accepted)
        assertEquals(42L, attributes["userId"])
        verify(sessionTokenService).authenticate("valid-token")
    }

    @Test
    fun `핸드셰이크는 userId query만으로는 연결을 허용하지 않는다`() {
        val sessionTokenService = mock(SessionTokenService::class.java)
        val interceptor = WebSocketHandshakeInterceptor(
            webSocketProperties = webSocketProperties,
            sessionTokenService = sessionTokenService,
            authProperties = authProperties,
        )
        val attributes = mutableMapOf<String?, Any?>()

        val accepted = interceptor.beforeHandshake(
            request("userId=42"),
            response(),
            mock(WebSocketHandler::class.java),
            attributes,
        )

        assertFalse(accepted)
        assertNull(attributes["userId"])
        verifyNoInteractions(sessionTokenService)
    }

    private fun request(queryString: String): ServerHttpRequest {
        val servletRequest = MockHttpServletRequest("GET", "/api/ws/chat")
        servletRequest.queryString = queryString
        return ServletServerHttpRequest(servletRequest)
    }

    private fun response(): ServerHttpResponse {
        return ServletServerHttpResponse(MockHttpServletResponse())
    }
}
