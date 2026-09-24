package com.chat.api.controller

import com.chat.api.config.MessagePaginationProperties
import com.chat.api.security.AuthenticatedUserResolver
import com.chat.core.dto.AuthenticatedSession
import com.chat.core.service.ChatService
import com.chat.core.service.SessionTokenService
import com.chat.domain.exception.ForbiddenOperationException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class ChatControllerAuthorizationTest {
    private val service = mock(ChatService::class.java)
    private val tokens = mock(SessionTokenService::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(ChatController(service, MessagePaginationProperties()))
        .setCustomArgumentResolvers(AuthenticatedUserResolver(tokens))
        .setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `anonymous room detail and members requests are rejected before service calls`() {
        listOf("/chat-rooms/10", "/chat-rooms/10/members").forEach { path ->
            mvc.get(path).andExpect { status { isUnauthorized() } }
        }
        verifyNoInteractions(service)
    }

    @Test
    fun `authenticated nonmember gets forbidden for both reads`() {
        `when`(tokens.authenticate("session")).thenReturn(AuthenticatedSession(8, LocalDateTime.now().plusHours(1)))
        `when`(service.getChatRoom(10, 8)).thenThrow(ForbiddenOperationException("not a member"))
        `when`(service.getChatRoomMembers(10, 8)).thenThrow(ForbiddenOperationException("not a member"))
        listOf("/chat-rooms/10", "/chat-rooms/10/members").forEach { path ->
            mvc.get(path) { header("Authorization", "Bearer session") }.andExpect { status { isForbidden() } }
        }
        verify(service).getChatRoom(10, 8)
        verify(service).getChatRoomMembers(10, 8)
    }
}
