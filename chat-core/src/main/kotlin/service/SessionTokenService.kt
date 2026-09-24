package com.chat.core.service

import com.chat.core.dto.AuthenticatedSession
import com.chat.core.dto.AuthenticatedWebSocketTicket
import com.chat.core.dto.SessionToken
import com.chat.core.dto.WebSocketTicketResponse

interface SessionTokenService {
    fun issueToken(userId: Long): SessionToken

    fun authenticate(token: String): AuthenticatedSession?

    fun revokeToken(token: String): Boolean

    fun revokeUserTokens(userId: Long)
}

interface WebSocketTicketService {
    fun issueTicket(userId: Long, clientIp: String?, sessionToken: String): WebSocketTicketResponse?

    fun consumeTicket(ticket: String): AuthenticatedWebSocketTicket?
}
