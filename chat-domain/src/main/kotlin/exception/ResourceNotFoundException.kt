package com.chat.domain.exception

/**
 * 요청한 리소스(사용자, 채팅방 등)를 찾을 수 없을 때 던진다.
 * HTTP 404 Not Found 로 매핑된다.
 */
class ResourceNotFoundException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
