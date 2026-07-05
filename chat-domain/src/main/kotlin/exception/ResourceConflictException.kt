package com.chat.domain.exception

/**
 * 현재 리소스 상태와 요청이 충돌할 때(중복 생성, 이미 참여한 채팅방 등) 던진다.
 * HTTP 409 Conflict 로 매핑된다.
 */
class ResourceConflictException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
