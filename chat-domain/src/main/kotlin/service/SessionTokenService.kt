package com.chat.domain.service

import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.dto.AuthenticatedWebSocketTicket
import com.chat.domain.dto.SessionToken
import com.chat.domain.dto.WebSocketTicketResponse

interface SessionTokenService {
    fun issueToken(userId: Long): SessionToken
    fun authenticate(token: String): AuthenticatedSession?
    fun revokeToken(token: String): Boolean
    fun revokeUserTokens(userId: Long)
}

interface WebSocketTicketService {
    fun issueTicket(userId: Long, clientIp: String?): WebSocketTicketResponse?
    fun consumeTicket(ticket: String): AuthenticatedWebSocketTicket?
}
