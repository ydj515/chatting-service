package com.chat.domain.exception

class MessageModerationRejectedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
