package com.chat.core.user.service

import com.chat.core.dto.LoginResponse
import com.chat.core.dto.UserDto
import com.chat.core.dto.UserSanctionType
import com.chat.core.mapping.toUserDto
import com.chat.core.service.SessionTokenService
import com.chat.core.service.UserService
import com.chat.core.user.command.CreateUserCommand
import com.chat.core.user.command.LoginCommand
import com.chat.core.user.port.LoginSanctionReader
import com.chat.core.user.port.PasswordHashing
import com.chat.core.user.port.UserStore
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.exception.ResourceNotFoundException
import com.chat.domain.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
@Transactional
class UserServiceImpl(
    private val userRepository: UserStore,
    private val sessionTokenService: SessionTokenService,
    private val userSanctionRepository: LoginSanctionReader,
    private val clock: Clock,
    private val passwordHashing: PasswordHashing,
) : UserService {
    override fun createUser(request: CreateUserCommand): UserDto {
        require(request.username.isNotBlank() && request.username.length in 3..20) { "username must contain 3 to 20 characters" }
        require(request.displayName.isNotBlank() && request.displayName.length <= 50) { "displayName must contain 1 to 50 characters" }
        require(request.password.length >= 3) { "password must contain at least 3 characters" }
        requireValidCredentials(request.username, request.password)
        // 이미 존재하는 사용자인지 확인
        if (userRepository.existsByUsername(request.username)) {
            throw ResourceConflictException("이미 존재하는 사용자명입니다: ${request.username}")
        }

        val user = User(
            username = request.username,
            password = passwordHashing.encode(request.password),
            displayName = request.displayName,
        )

        val savedUser = userRepository.save(user)
        return savedUser.toUserDto()
    }

    override fun login(request: LoginCommand): LoginResponse {
        requireValidCredentials(request.username, request.password)
        val user = userRepository.findByUsername(request.username)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없거나 비밀번호가 일치하지 않습니다.")

        val verification = passwordHashing.verify(request.password, user.password)
        require(verification.matched) { "사용자를 찾을 수 없거나 비밀번호가 일치하지 않습니다." }

        requireNotSuspended(user.id)

        if (verification.requiresRehash) {
            userRepository.updatePassword(user.id, passwordHashing.encode(request.password))
        }

        val sessionToken = sessionTokenService.issueToken(user.id)
        return LoginResponse(
            user = user.toUserDto(),
            sessionToken = sessionToken.token,
            expiresAt = sessionToken.expiresAt,
        )
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun logout(sessionToken: String) {
        sessionTokenService.revokeToken(sessionToken)
    }

    @Transactional(readOnly = true)
    override fun getUserById(userId: Long): UserDto {
        val user = userRepository.findById(userId)
            ?: throw ResourceNotFoundException("사용자를 찾을 수 없습니다: $userId")
        return user.toUserDto()
    }

    @Transactional(readOnly = true)
    override fun searchUsers(
        query: String,
        pageable: Pageable,
    ): Page<UserDto> = userRepository.searchUsers(query, pageable).map { it.toUserDto() }

    override fun updateLastSeen(userId: Long): UserDto {
        val user = userRepository.findById(userId)
            ?: throw ResourceNotFoundException("사용자를 찾을 수 없습니다: $userId")

        val now = LocalDateTime.now(clock)
        userRepository.updateLastSeenAt(userId, now)

        return user.toUserDto().copy(lastSeenAt = now)
    }

    private fun requireValidCredentials(username: String, password: String) {
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotBlank() && password.toByteArray(Charsets.UTF_8).size <= 72) { "password must contain 1 to 72 UTF-8 bytes" }
    }

    private fun requireNotSuspended(userId: Long) {
        val now = clock.instant()
        val suspended = userSanctionRepository.activeGlobalSanctionsForUser(userId)
            .any { sanction ->
                sanction.type == UserSanctionType.SUSPEND &&
                    (sanction.expiresAt == null || sanction.expiresAt.isAfter(now))
            }
        check(!suspended) { "정지된 사용자는 로그인할 수 없습니다." }
    }
}
