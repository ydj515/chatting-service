package com.chat.domain.exception

/**
 * 인증은 되었지만 해당 리소스에 대한 권한이 없을 때(채팅방 비참여자의 접근 등) 던진다.
 * HTTP 403 Forbidden 으로 매핑된다.
 */
class ForbiddenOperationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
