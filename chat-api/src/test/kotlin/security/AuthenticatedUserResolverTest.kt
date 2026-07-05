package com.chat.api.security

import com.chat.api.controller.GlobalExceptionHandler
import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.service.SessionTokenService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

class AuthenticatedUserResolverTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var sessionTokenService: SessionTokenService

    @BeforeEach
    fun setUp() {
        sessionTokenService = mock(SessionTokenService::class.java)
        mockMvc = MockMvcBuilders
            .standaloneSetup(TestAuthController())
            .setCustomArgumentResolvers(AuthenticatedUserResolver(sessionTokenService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `CurrentUserId parameter는 Authorization bearer token을 인증해 사용자 ID로 resolve한다`() {
        `when`(sessionTokenService.authenticate("valid-token")).thenReturn(
            AuthenticatedSession(
                userId = 42L,
                expiresAt = LocalDateTime.parse("2026-06-12T12:30:00"),
            )
        )

        mockMvc.get("/test/current-user") {
            header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(42) }
        }

        verify(sessionTokenService).authenticate("valid-token")
    }

    @Test
    fun `CurrentUserId parameter는 token이 없으면 401로 응답한다`() {
        mockMvc.get("/test/current-user").andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("인증 토큰이 필요합니다.") }
        }
    }

    @Test
    fun `CurrentUserId parameter는 Authorization token이 유효하지 않으면 401로 응답한다`() {
        `when`(sessionTokenService.authenticate("bad-token")).thenReturn(null)

        mockMvc.get("/test/current-user") {
            header(HttpHeaders.AUTHORIZATION, "Bearer bad-token")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("유효하지 않은 인증 토큰입니다.") }
        }
    }

    @Test
    fun `CurrentSessionToken parameter는 bearer token 문자열을 trim해서 resolve한다`() {
        mockMvc.post("/test/current-session-token") {
            header(HttpHeaders.AUTHORIZATION, "Bearer   logout-token  ")
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { value("logout-token") }
        }

        verifyNoInteractions(sessionTokenService)
    }

    @Test
    fun `CurrentSessionToken parameter는 bearer token이 아니면 401로 응답한다`() {
        mockMvc.post("/test/current-session-token") {
            header(HttpHeaders.AUTHORIZATION, "Basic logout-token")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("인증 토큰이 필요합니다.") }
        }
    }

    @RestController
    private class TestAuthController {
        @GetMapping("/test/current-user")
        fun currentUser(@CurrentUserId userId: Long): Map<String, Long> {
            return mapOf("userId" to userId)
        }

        @PostMapping("/test/current-session-token")
        fun currentSessionToken(@CurrentSessionToken sessionToken: String): Map<String, String> {
            return mapOf("token" to sessionToken)
        }
    }
}
