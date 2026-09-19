package com.chat.persistence.service

import java.security.MessageDigest
import java.util.Base64

internal object SessionTokenDigests {
    fun sha256(token: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
}
