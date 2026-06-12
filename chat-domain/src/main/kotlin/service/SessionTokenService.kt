package com.chat.domain.service

import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.dto.SessionToken

interface SessionTokenService {
    fun issueToken(userId: Long): SessionToken
    fun authenticate(token: String): AuthenticatedSession?
}
