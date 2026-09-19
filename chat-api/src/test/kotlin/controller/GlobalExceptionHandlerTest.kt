package com.chat.api.controller

import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.exception.ForbiddenOperationException
import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.exception.ResourceNotFoundException
import jakarta.validation.Valid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
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
            content =
                """
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
    fun `회원가입 비밀번호는 BCrypt 한계인 72 UTF-8 bytes를 초과할 수 없다`() {
        mockMvc.post("/test/users") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "username": "tester",
                  "password": "${"a".repeat(73)}",
                  "displayName": "테스터"
                }
                """.trimIndent()
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[?(@.field == 'password' && @.message == '비밀번호는 UTF-8 기준 72바이트 이하여야 합니다')]") { exists() }
            }
    }

    @Test
    fun `로그인 비밀번호도 BCrypt 한계인 72 UTF-8 bytes를 초과할 수 없다`() {
        mockMvc.post("/test/login") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "username": "tester",
                  "password": "${"한".repeat(25)}"
                }
                """.trimIndent()
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[?(@.field == 'password' && @.message == '비밀번호는 UTF-8 기준 72바이트 이하여야 합니다')]") { exists() }
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
    fun `ResourceNotFoundException은 404 응답으로 변환한다`() {
        mockMvc.get("/test/not-found")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
                jsonPath("$.error") { value("NOT_FOUND") }
                jsonPath("$.message") { value("채팅방을 찾을 수 없습니다: 1") }
                jsonPath("$.path") { value("/test/not-found") }
            }
    }

    @Test
    fun `ResourceConflictException은 409 응답으로 변환한다`() {
        mockMvc.get("/test/resource-conflict")
            .andExpect {
                status { isConflict() }
                jsonPath("$.status") { value(409) }
                jsonPath("$.error") { value("CONFLICT") }
                jsonPath("$.message") { value("이미 존재하는 사용자명입니다: tester") }
                jsonPath("$.path") { value("/test/resource-conflict") }
            }
    }

    @Test
    fun `ForbiddenOperationException은 403 응답으로 변환한다`() {
        mockMvc.get("/test/forbidden")
            .andExpect {
                status { isForbidden() }
                jsonPath("$.status") { value(403) }
                jsonPath("$.error") { value("FORBIDDEN") }
                jsonPath("$.message") { value("채팅방 멤버가 아닙니다") }
                jsonPath("$.path") { value("/test/forbidden") }
            }
    }

    @Test
    fun `메시지 수락 정책 거부는 429 응답으로 변환한다`() {
        mockMvc.get("/test/message-admission-rejected")
            .andExpect {
                status { isTooManyRequests() }
                jsonPath("$.status") { value(429) }
                jsonPath("$.error") { value("TOO_MANY_REQUESTS") }
                jsonPath("$.message") { value("room rate limit exceeded") }
                jsonPath("$.path") { value("/test/message-admission-rejected") }
            }
    }

    @Test
    fun `moderation 거부는 403 응답으로 변환한다`() {
        mockMvc.get("/test/message-moderation-rejected")
            .andExpect {
                status { isForbidden() }
                jsonPath("$.status") { value(403) }
                jsonPath("$.error") { value("FORBIDDEN") }
                jsonPath("$.message") { value("message blocked by moderation policy") }
                jsonPath("$.path") { value("/test/message-moderation-rejected") }
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

        @PostMapping("/test/login")
        fun login(@Valid @RequestBody request: LoginRequest): LoginRequest = request

        @GetMapping("/test/bad-request")
        fun badRequest(): String = throw IllegalArgumentException("잘못된 요청입니다.")

        @GetMapping("/test/conflict")
        fun conflict(): String = error("이미 참여한 채팅방입니다")

        @GetMapping("/test/not-found")
        fun notFound(): String = throw ResourceNotFoundException("채팅방을 찾을 수 없습니다: 1")

        @GetMapping("/test/resource-conflict")
        fun resourceConflict(): String = throw ResourceConflictException("이미 존재하는 사용자명입니다: tester")

        @GetMapping("/test/forbidden")
        fun forbidden(): String = throw ForbiddenOperationException("채팅방 멤버가 아닙니다")

        @GetMapping("/test/message-admission-rejected")
        fun messageAdmissionRejected(): String = throw MessageAdmissionRejectedException("room rate limit exceeded")

        @GetMapping("/test/message-moderation-rejected")
        fun messageModerationRejected(): String =
            throw MessageModerationRejectedException("message blocked by moderation policy")

        @GetMapping("/test/server-error")
        fun serverError(): String = throw RuntimeException("database password leaked in stack trace")
    }
}
