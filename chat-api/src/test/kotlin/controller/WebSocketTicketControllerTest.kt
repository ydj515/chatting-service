package com.chat.api.controller

import com.chat.api.security.AuthenticatedUserResolver
import com.chat.domain.dto.WebSocketTicketResponse
import com.chat.domain.service.SessionTokenService
import com.chat.domain.service.WebSocketTicketService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.http.HttpHeaders
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class WebSocketTicketControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var ticketService: WebSocketTicketService
    private lateinit var sessionTokenService: SessionTokenService

    @BeforeEach
    fun setUp() {
        ticketService = mock(WebSocketTicketService::class.java)
        sessionTokenService = mock(SessionTokenService::class.java)
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                WebSocketTicketController(
                    authenticatedUserResolver = AuthenticatedUserResolver(sessionTokenService),
                    webSocketTicketService = ticketService,
                ),
            )
            .setControllerAdvice(GlobalExceptionHandler())
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
    fun `인증된 사용자는 WebSocket one-time ticket을 발급받는다`() {
        `when`(sessionTokenService.authenticate("session-token")).thenReturn(
            com.chat.domain.dto.AuthenticatedSession(
                userId = 42L,
                expiresAt = LocalDateTime.parse("2026-06-13T00:30:00"),
            ),
        )
        `when`(ticketService.issueTicket(42L, "127.0.0.1")).thenReturn(
            WebSocketTicketResponse(
                ticket = "ticket-value",
                expiresAt = LocalDateTime.parse("2026-06-13T00:00:30"),
            ),
        )

        mockMvc.post("/ws-tickets") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            with { request ->
                request.remoteAddr = "127.0.0.1"
                request
            }
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.ticket") { value("ticket-value") }
                jsonPath("$.expiresAt") { value("2026-06-13T00:00:30") }
            }

        verify(ticketService).issueTicket(42L, "127.0.0.1")
    }

    @Test
    fun `ticket 발급 rate limit 초과는 429로 응답한다`() {
        `when`(sessionTokenService.authenticate("session-token")).thenReturn(
            com.chat.domain.dto.AuthenticatedSession(
                userId = 42L,
                expiresAt = LocalDateTime.parse("2026-06-13T00:30:00"),
            ),
        )
        `when`(ticketService.issueTicket(42L, "127.0.0.1")).thenReturn(null)

        mockMvc.post("/ws-tickets") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            with { request ->
                request.remoteAddr = "127.0.0.1"
                request
            }
        }
            .andExpect {
                status { isTooManyRequests() }
            }
    }
}
