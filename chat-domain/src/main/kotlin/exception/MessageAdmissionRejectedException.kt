package com.chat.domain.exception

class MessageAdmissionRejectedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
