package com.chat.core.service

import com.chat.core.dto.*
import com.chat.core.user.command.CreateUserCommand
import com.chat.core.user.command.LoginCommand
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface UserService {
    // 사용자 관리
    fun createUser(request: CreateUserCommand): UserDto

    fun login(request: LoginCommand): LoginResponse

    fun logout(sessionToken: String)

    fun getUserById(userId: Long): UserDto

    fun searchUsers(query: String, pageable: Pageable): Page<UserDto>

    // 사용자 상태
    fun updateLastSeen(userId: Long): UserDto
}
