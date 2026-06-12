package com.chat.api.controller

import com.chat.domain.dto.CreateUserRequest
import jakarta.validation.Valid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

class GlobalExceptionHandlerTest {

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(TestErrorController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `validation 오류는 errors 배열을 포함한 400 응답으로 변환한다`() {
        mockMvc.post("/test/users") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "username": "ab",
                  "password": "12",
                  "displayName": ""
                }
            """.trimIndent()
        }
            .andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.status") { value(400) }
                jsonPath("$.error") { value("BAD_REQUEST") }
                jsonPath("$.message") { value("입력값 검증에 실패했습니다.") }
                jsonPath("$.path") { value("/test/users") }
                jsonPath("$.timestamp") { exists() }
                jsonPath("$.errors[?(@.field == 'username' && @.message == '사용자명은 3-20자 사이여야 합니다')]") { exists() }
                jsonPath("$.errors[?(@.field == 'password' && @.message == '비밀번호는 최소 3자 이상이어야 합니다')]") { exists() }
                jsonPath("$.errors[?(@.field == 'displayName' && @.message == '표시 이름은 필수입니다')]") { exists() }
            }
    }

    @Test
    fun `IllegalArgumentException은 400 응답으로 변환한다`() {
        mockMvc.get("/test/bad-request")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.status") { value(400) }
                jsonPath("$.error") { value("BAD_REQUEST") }
                jsonPath("$.message") { value("잘못된 요청입니다.") }
                jsonPath("$.path") { value("/test/bad-request") }
                jsonPath("$.errors") { doesNotExist() }
            }
    }

    @Test
    fun `IllegalStateException은 409 응답으로 변환한다`() {
        mockMvc.get("/test/conflict")
            .andExpect {
                status { isConflict() }
                jsonPath("$.status") { value(409) }
                jsonPath("$.error") { value("CONFLICT") }
                jsonPath("$.message") { value("이미 참여한 채팅방입니다") }
                jsonPath("$.path") { value("/test/conflict") }
            }
    }

    @Test
    fun `예상하지 못한 예외는 상세 내용을 숨긴 500 응답으로 변환한다`() {
        mockMvc.get("/test/server-error")
            .andExpect {
                status { isInternalServerError() }
                jsonPath("$.status") { value(500) }
                jsonPath("$.error") { value("INTERNAL_SERVER_ERROR") }
                jsonPath("$.message") { value("서버 내부 오류가 발생했습니다.") }
                jsonPath("$.path") { value("/test/server-error") }
            }
    }

    @RestController
    private class TestErrorController {
        @PostMapping("/test/users")
        fun createUser(@Valid @RequestBody request: CreateUserRequest): CreateUserRequest = request

        @GetMapping("/test/bad-request")
        fun badRequest(): String {
            throw IllegalArgumentException("잘못된 요청입니다.")
        }

        @GetMapping("/test/conflict")
        fun conflict(): String {
            throw IllegalStateException("이미 참여한 채팅방입니다")
        }

        @GetMapping("/test/server-error")
        fun serverError(): String {
            throw RuntimeException("database password leaked in stack trace")
        }
    }
}
