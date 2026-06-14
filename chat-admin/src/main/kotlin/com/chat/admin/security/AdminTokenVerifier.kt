package com.chat.admin.security

import com.chat.admin.config.AdminProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@Component
class AdminTokenVerifier(
    private val properties: AdminProperties,
) {
    fun requireActor(token: String?): String {
        if (token.isNullOrBlank() || !constantTimeEquals(token, properties.token)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token")
        }
        return properties.actor
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }
}
