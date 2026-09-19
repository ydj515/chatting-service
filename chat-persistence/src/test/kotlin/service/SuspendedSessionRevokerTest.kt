package com.chat.persistence.service

import com.chat.domain.service.SessionControlPublisher
import com.chat.domain.service.SessionTokenService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class SuspendedSessionRevokerTest {
    @Test
    fun `token revocation failure still attempts session logout and remains observable`() {
        val tokens = mock(SessionTokenService::class.java)
        val sessions = mock(SessionControlPublisher::class.java)
        doThrow(IllegalStateException("unavailable")).`when`(tokens).revokeUserTokens(7)
        assertThrows(IllegalStateException::class.java) { SuspendedSessionRevoker(tokens, sessions).revokeAfterCommit(7) }
        verify(sessions).forceLogoutUser(7, "suspended")
    }
}
