package com.chat.websocket.interceptor

import com.chat.domain.service.SessionTokenService
import com.chat.domain.service.WebSocketTicketService
import com.chat.persistence.config.ChatAuthProperties
import com.chat.websocket.config.WebSocketProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder

@Component
class WebSocketHandshakeInterceptor(
    private val webSocketProperties: WebSocketProperties,
    private val sessionTokenService: SessionTokenService,
    private val webSocketTicketService: WebSocketTicketService,
    private val authProperties: ChatAuthProperties,
) : HandshakeInterceptor {

    private val logger = LoggerFactory.getLogger(WebSocketHandshakeInterceptor::class.java)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String?, Any?>,
    ): Boolean {
        return try {
            val ticket = extractQueryParam(request, authProperties.webSocketTicket.ticketQueryParam)
            val ticketSession = ticket?.let { webSocketTicketService.consumeTicket(it) }
            if (ticketSession != null) {
                attributes[webSocketProperties.userIdAttribute] = ticketSession.userId
                return true
            }

            if (!authProperties.webSocketTicket.sessionFallbackEnabled) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED)
                return false
            }

            val token = extractSessionToken(request)
            val authenticated = token?.let { sessionTokenService.authenticate(it) }

            if (authenticated == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED)
                false
            } else {
                attributes[webSocketProperties.userIdAttribute] = authenticated.userId
                true
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            false
        }
    }

    private fun extractSessionToken(request: ServerHttpRequest): String? {
        val authorizationHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        val bearerToken = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (bearerToken != null) {
            return bearerToken
        }

        return extractQueryParam(request, authProperties.session.tokenQueryParam)
    }

    private fun extractQueryParam(request: ServerHttpRequest, name: String): String? {
        return UriComponentsBuilder.fromUri(request.uri)
            .build()
            .queryParams
            .getFirst(name)
            ?.takeIf { it.isNotBlank() }
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

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
