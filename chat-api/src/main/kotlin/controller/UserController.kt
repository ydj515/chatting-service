package com.chat.api.controller

import com.chat.api.dto.CreateUserRequest
import com.chat.api.dto.LoginRequest
import com.chat.api.dto.toCommand
import com.chat.api.security.CurrentSessionToken
import com.chat.api.security.CurrentUserId
import com.chat.core.dto.*
import com.chat.core.service.UserService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {
    @PostMapping("/register")
    fun register(@Valid @RequestBody request: CreateUserRequest): ResponseEntity<UserDto> {
        val user = userService.createUser(request.toCommand())
        return ResponseEntity.ok(user)
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {
        val response = userService.login(request.toCommand())
        return ResponseEntity.ok(response)
    }

    @PostMapping("/logout")
    fun logout(@CurrentSessionToken sessionToken: String): ResponseEntity<Unit> {
        userService.logout(sessionToken)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: Long): ResponseEntity<UserDto> {
        val user = userService.getUserById(id)
        return ResponseEntity.ok(user)
    }

    @GetMapping("/me")
    fun getCurrentUser(@CurrentUserId userId: Long): ResponseEntity<UserDto> {
        val user = userService.getUserById(userId)
        return ResponseEntity.ok(user)
    }

    @GetMapping("/search")
    fun searchUsers(
        @RequestParam username: String,
        @PageableDefault(size = 10) pageable: Pageable,
    ): ResponseEntity<Page<UserDto>> {
        val users = userService.searchUsers(username, pageable)
        return ResponseEntity.ok(users)
    }
}
