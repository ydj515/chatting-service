package com.chat.api.controller

import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.dto.LoginResponse
import com.chat.domain.dto.UserDto
import com.chat.domain.service.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class UserControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var userService: RecordingUserService

    @BeforeEach
    fun setUp() {
        userService = RecordingUserService()
        mockMvc = MockMvcBuilders
            .standaloneSetup(UserController(userService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `logout은 bearer token을 revoke 대상으로 전달하고 204로 응답한다`() {
        mockMvc.post("/users/logout") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
        }.andExpect {
            status { isNoContent() }
        }

        assertEquals("session-token", userService.logoutToken)
    }

    private class RecordingUserService : UserService {
        var logoutToken: String? = null

        override fun createUser(request: CreateUserRequest): UserDto {
            throw UnsupportedOperationException()
        }

        override fun login(request: LoginRequest): LoginResponse {
            throw UnsupportedOperationException()
        }

        override fun logout(sessionToken: String) {
            logoutToken = sessionToken
        }

        override fun getUserById(userId: Long): UserDto {
            throw UnsupportedOperationException()
        }

        override fun searchUsers(query: String, pageable: Pageable): Page<UserDto> {
            throw UnsupportedOperationException()
        }

        override fun updateLastSeen(userId: Long): UserDto {
            throw UnsupportedOperationException()
        }
    }
}
