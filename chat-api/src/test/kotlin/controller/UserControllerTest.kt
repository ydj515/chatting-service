package com.chat.api.controller

import com.chat.api.security.FixedCurrentAuthenticationResolver
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.dto.LoginResponse
import com.chat.domain.dto.UserDto
import com.chat.domain.service.UserService
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class UserControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var userService: RecordingUserService

    @BeforeEach
    fun setUp() {
        userService = RecordingUserService()
        mockMvc = MockMvcBuilders
            .standaloneSetup(UserController(userService))
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(FixedCurrentAuthenticationResolver(userId = 42L, sessionToken = "resolved-token"))
            .setMessageConverters(
                MappingJackson2HttpMessageConverter(
                    ObjectMapper()
                        .registerModule(JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                ),
            )
            .build()
    }

    @Test
    fun `logout은 argument resolver가 제공한 session token을 revoke 대상으로 전달하고 204로 응답한다`() {
        mockMvc.post("/users/logout").andExpect {
            status { isNoContent() }
        }

        assertEquals("resolved-token", userService.logoutToken)
    }

    @Test
    fun `me 조회는 argument resolver가 제공한 현재 사용자 ID로 조회한다`() {
        userService.userToReturn = userDto(42L)

        mockMvc.get("/users/me").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(42) }
        }

        assertEquals(42L, userService.requestedUserId)
    }

    @Test
    fun `초기 상태에서는 revoke 대상 token이 없다`() {
        assertNull(userService.logoutToken)
    }

    private class RecordingUserService : UserService {
        var logoutToken: String? = null
        var requestedUserId: Long? = null
        var userToReturn: UserDto? = null

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
            requestedUserId = userId
            return userToReturn ?: throw UnsupportedOperationException()
        }

        override fun searchUsers(query: String, pageable: Pageable): Page<UserDto> {
            throw UnsupportedOperationException()
        }

        override fun updateLastSeen(userId: Long): UserDto {
            throw UnsupportedOperationException()
        }
    }

    private fun userDto(id: Long): UserDto {
        return UserDto(
            id = id,
            username = "tester",
            displayName = "테스터",
            profileImageUrl = null,
            status = null,
            isActive = true,
            lastSeenAt = null,
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
    }
}
