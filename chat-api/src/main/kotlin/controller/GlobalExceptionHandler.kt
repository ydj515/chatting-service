package com.chat.api.controller

import com.chat.domain.exception.MessageAdmissionRejectedException
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.LocalDateTime

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val errors = exception.bindingResult.fieldErrors.map { it.toFieldErrorResponse() }

        return buildResponse(
            status = HttpStatus.BAD_REQUEST,
            message = "입력값 검증에 실패했습니다.",
            path = request.requestURI,
            errors = errors,
        )
    }

    @ExceptionHandler(
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleBadRequestException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        return buildResponse(
            status = HttpStatus.BAD_REQUEST,
            message = exception.message ?: "잘못된 요청입니다.",
            path = request.requestURI,
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(
        exception: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        return buildResponse(
            status = HttpStatus.BAD_REQUEST,
            message = exception.message ?: "잘못된 요청입니다.",
            path = request.requestURI,
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalStateException(
        exception: IllegalStateException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        return buildResponse(
            status = HttpStatus.CONFLICT,
            message = exception.message ?: "요청 상태가 현재 리소스 상태와 충돌합니다.",
            path = request.requestURI,
        )
    }

    @ExceptionHandler(MessageAdmissionRejectedException::class)
    fun handleMessageAdmissionRejectedException(
        exception: MessageAdmissionRejectedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        return buildResponse(
            status = HttpStatus.TOO_MANY_REQUESTS,
            message = exception.message ?: "메시지 전송 제한을 초과했습니다.",
            path = request.requestURI,
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        logger.error("Unhandled API exception", exception)

        return buildResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            message = "서버 내부 오류가 발생했습니다.",
            path = request.requestURI,
        )
    }

    private fun buildResponse(
        status: HttpStatus,
        message: String,
        path: String,
        errors: List<ApiFieldErrorResponse>? = null,
    ): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.status(status).body(
            ApiErrorResponse(
                status = status.value(),
                error = status.name,
                message = message,
                path = path,
                errors = errors.orEmpty(),
            )
        )
    }

    private fun FieldError.toFieldErrorResponse(): ApiFieldErrorResponse {
        return ApiFieldErrorResponse(
            field = field,
            message = defaultMessage ?: "유효하지 않은 값입니다.",
            rejectedValue = rejectedValue,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ApiErrorResponse(
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val errors: List<ApiFieldErrorResponse> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiFieldErrorResponse(
    val field: String,
    val message: String,
    val rejectedValue: Any?,
)
