package com.chat.api.controller

import com.chat.api.security.AuthenticatedUserResolver
import com.chat.domain.dto.WebSocketTicketResponse
import com.chat.domain.service.WebSocketTicketService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class WebSocketTicketController(
    private val authenticatedUserResolver: AuthenticatedUserResolver,
    private val webSocketTicketService: WebSocketTicketService,
) {

    @PostMapping("/ws-tickets")
    fun issueTicket(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorizationHeader: String?,
        request: HttpServletRequest,
    ): ResponseEntity<WebSocketTicketResponse> {
        val userId = authenticatedUserResolver.resolveRequired(authorizationHeader)
        val ticket = webSocketTicketService.issueTicket(userId, clientIp(request))
            ?: return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build()

        return ResponseEntity.ok(ticket)
    }

    private fun clientIp(request: HttpServletRequest): String? {
        return request.getHeader(X_FORWARDED_FOR)
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val X_FORWARDED_FOR = "X-Forwarded-For"
    }
}
