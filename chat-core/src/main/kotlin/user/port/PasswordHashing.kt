package com.chat.core.user.port

interface PasswordHashing {
    fun encode(password: String): String

    fun verify(password: String, storedPassword: String): PasswordVerification
}

data class PasswordVerification(val matched: Boolean, val requiresRehash: Boolean)
