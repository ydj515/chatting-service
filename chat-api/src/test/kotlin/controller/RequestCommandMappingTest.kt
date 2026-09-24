package com.chat.api.controller

import com.chat.api.config.MessagePaginationProperties
import com.chat.api.security.FixedCurrentAuthenticationResolver
import com.chat.core.dto.ChatRoomDto
import com.chat.core.dto.LoginResponse
import com.chat.core.dto.UserDto
import com.chat.core.room.command.CreateChatRoomCommand
import com.chat.core.service.ChatService
import com.chat.core.service.UserService
import com.chat.core.user.command.CreateUserCommand
import com.chat.core.user.command.LoginCommand
import com.chat.domain.model.ChatRoomType
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class RequestCommandMappingTest {
    private val users = mock(UserService::class.java)
    private val chats = mock(ChatService::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(UserController(users), ChatController(chats, MessagePaginationProperties()))
        .setControllerAdvice(GlobalExceptionHandler())
        .setCustomArgumentResolvers(FixedCurrentAuthenticationResolver(userId = 42))
        .build()
    private val createdAt = LocalDateTime.parse("2026-09-25T00:00:00")
    private val user = UserDto(42, "tester", "Tester", null, null, true, null, createdAt)

    @Test
    fun `registration and login JSON map to application commands`() {
        val registration = CreateUserCommand("tester", "password", "Tester")
        val login = LoginCommand("tester", "password")
        `when`(users.createUser(registration)).thenReturn(user)
        `when`(users.login(login)).thenReturn(LoginResponse(user, "token", expiresAt = createdAt.plusHours(1)))
        mvc.post("/users/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"tester","password":"password","displayName":"Tester"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(42) }
        }
        mvc.post("/users/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"tester","password":"password"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.sessionToken") { value("token") }
        }
        verify(users).createUser(registration)
        verify(users).login(login)
    }

    @Test
    fun `room creation retains nullable fields capacity default and authenticated creator`() {
        val command = CreateChatRoomCommand("Room", null, ChatRoomType.GROUP, null, 100)
        `when`(chats.createChatRoom(command, 42)).thenReturn(ChatRoomDto(10, "Room", null, ChatRoomType.GROUP, null, true, 100, 1, user, createdAt, null))
        mvc.post("/chat-rooms") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Room","type":"GROUP"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.maxMembers") { value(100) }
        }
        verify(chats).createChatRoom(command, 42)
    }

    @Test
    fun `invalid transport requests fail before invoking use cases`() {
        mvc.post("/users/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"x","password":"password","displayName":"Tester"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.post("/chat-rooms") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Room","type":"GROUP","maxMembers":0}"""
        }.andExpect { status { isBadRequest() } }
        verifyNoInteractions(users, chats)
    }
}
