package com.chat.websocket.interceptor

import com.chat.websocket.config.WebSocketProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder

@Component
class WebSocketHandshakeInterceptor(
    private val webSocketProperties: WebSocketProperties,
) : HandshakeInterceptor {

    private val logger = LoggerFactory.getLogger(WebSocketHandshakeInterceptor::class.java)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String?, Any?>,
    ): Boolean {
        return try {
            val userId = UriComponentsBuilder.fromUri(request.uri)
                .build()
                .queryParams
                .getFirst(webSocketProperties.userIdQueryParam)
                ?.toLongOrNull()

            userId?.let {
                attributes[webSocketProperties.userIdAttribute] = it
                true
            } ?: false
        } catch (e: Exception) {
            logger.error(e.message, e)
            false
        }
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {
        if (exception != null) {
            logger.error("WebSocket HandshakeInterceptor exception", exception)
        } else {
            logger.info("WebSocket HandshakeInterceptor")
        }
    }
}
