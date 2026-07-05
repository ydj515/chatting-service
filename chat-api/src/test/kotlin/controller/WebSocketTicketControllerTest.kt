package com.chat.api.controller

import com.chat.api.security.CurrentUserId
import com.chat.domain.dto.WebSocketTicketResponse
import com.chat.domain.service.WebSocketTicketService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.core.MethodParameter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.LocalDateTime

class WebSocketTicketControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var ticketService: WebSocketTicketService

    @BeforeEach
    fun setUp() {
        ticketService = mock(WebSocketTicketService::class.java)
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                WebSocketTicketController(
                    webSocketTicketService = ticketService,
                ),
            )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(FixedCurrentUserIdResolver(42L))
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
        `when`(ticketService.issueTicket(42L, "127.0.0.1")).thenReturn(
            WebSocketTicketResponse(
                ticket = "ticket-value",
                expiresAt = LocalDateTime.parse("2026-06-13T00:00:30"),
            ),
        )

        mockMvc.post("/ws-tickets") {
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
        `when`(ticketService.issueTicket(42L, "127.0.0.1")).thenReturn(null)

        mockMvc.post("/ws-tickets") {
            with { request ->
                request.remoteAddr = "127.0.0.1"
                request
            }
        }
            .andExpect {
                status { isTooManyRequests() }
            }
    }

    private class FixedCurrentUserIdResolver(
        private val userId: Long,
    ) : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter): Boolean {
            return parameter.hasParameterAnnotation(CurrentUserId::class.java)
        }

        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?,
        ): Any {
            return userId
        }
    }
}
