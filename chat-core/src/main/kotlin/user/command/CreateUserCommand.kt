package com.chat.core.user.command

data class CreateUserCommand(
    val username: String,
    val password: String,
    val displayName: String,
)
