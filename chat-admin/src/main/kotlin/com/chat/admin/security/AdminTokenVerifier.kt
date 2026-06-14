package com.chat.admin.security

import com.chat.admin.config.AdminProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class AdminTokenVerifier(
    private val properties: AdminProperties,
) {
    fun requireActor(token: String?): String {
        if (token.isNullOrBlank() || token != properties.token) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token")
        }
        return properties.actor
    }
}
