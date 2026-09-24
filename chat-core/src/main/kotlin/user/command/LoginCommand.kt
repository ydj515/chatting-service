package com.chat.core.user.command

data class LoginCommand(
    val username: String,
    val password: String,
)
