package com.chat.admin.security

import com.chat.admin.config.AdminProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class AdminTokenVerifierTest {

    @Test
    fun `valid token returns configured actor`() {
        val verifier = AdminTokenVerifier(
            AdminProperties(
                token = "local-admin-token",
                actor = "admin-local",
            ),
        )

        assertEquals("admin-local", verifier.requireActor("local-admin-token"))
    }

    @Test
    fun `invalid token is rejected`() {
        val verifier = AdminTokenVerifier(
            AdminProperties(
                token = "local-admin-token",
                actor = "admin-local",
            ),
        )

        assertThrows(ResponseStatusException::class.java) {
            verifier.requireActor("local-admin-tokem")
        }
    }
}
