package com.chat.persistence.service

import com.chat.domain.dto.CreateUserRequest
import com.chat.domain.dto.LoginRequest
import com.chat.domain.dto.LoginResponse
import com.chat.domain.dto.UserDto
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.exception.ResourceNotFoundException
import com.chat.domain.model.User
import com.chat.domain.service.SessionTokenService
import com.chat.domain.service.UserService
import com.chat.persistence.repository.UserRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime

@Service
@Transactional
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val sessionTokenService: SessionTokenService,
    private val userSanctionRepository: UserSanctionJdbcRepository,
    private val clock: Clock,
    private val passwordEncoder: PasswordEncoder,
) : UserService {
    override fun createUser(request: CreateUserRequest): UserDto {
        // 이미 존재하는 사용자인지 확인
        if (userRepository.existsByUsername(request.username)) {
            throw ResourceConflictException("이미 존재하는 사용자명입니다: ${request.username}")
        }

        val user = User(
            username = request.username,
            password = encodeBcryptPassword(request.password),
            displayName = request.displayName,
        )

        val savedUser = userRepository.save(user)
        return userToDto(savedUser)
    }

    override fun login(request: LoginRequest): LoginResponse {
        val user = userRepository.findByUsername(request.username)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없거나 비밀번호가 일치하지 않습니다.")

        val verification = verifyPassword(request.password, user.password)
        require(verification.matched) { "사용자를 찾을 수 없거나 비밀번호가 일치하지 않습니다." }

        requireNotSuspended(user.id)

        if (verification.requiresRehash) {
            userRepository.updatePassword(user.id, encodeBcryptPassword(request.password))
        }

        val sessionToken = sessionTokenService.issueToken(user.id)
        return LoginResponse(
            user = userToDto(user),
            sessionToken = sessionToken.token,
            expiresAt = sessionToken.expiresAt,
        )
    }

    override fun logout(sessionToken: String) {
        sessionTokenService.revokeToken(sessionToken)
    }

    @Transactional(readOnly = true)
    override fun getUserById(userId: Long): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다: $userId") }
        return userToDto(user)
    }

    @Transactional(readOnly = true)
    override fun searchUsers(
        query: String,
        pageable: Pageable,
    ): Page<UserDto> = userRepository.searchUsers(query, pageable).map { userToDto(it) }

    override fun updateLastSeen(userId: Long): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다: $userId") }

        val now = LocalDateTime.now(clock)
        userRepository.updateLastSeenAt(userId, now)

        // 엔티티는 더 이상 data class 가 아니므로 값 객체인 DTO 에서 copy 한다.
        return userToDto(user).copy(lastSeenAt = now)
    }

    private fun encodeBcryptPassword(password: String): String {
        requireBcryptCompatiblePassword(password)
        return passwordEncoder.encode(password)
    }

    private fun verifyPassword(password: String, storedPassword: String): PasswordVerification {
        if (isLegacySha256Hash(storedPassword)) {
            return PasswordVerification(
                matched = legacySha256Matches(password, storedPassword),
                requiresRehash = isBcryptCompatiblePassword(password),
            )
        }

        if (!isBcryptCompatiblePassword(password)) {
            return PasswordVerification(matched = false, requiresRehash = false)
        }

        return PasswordVerification(
            matched = passwordEncoder.matches(password, storedPassword),
            requiresRehash = false,
        )
    }

    private fun legacySha256Matches(password: String, storedPassword: String): Boolean {
        val expected = legacySha256(password)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            storedPassword.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun legacySha256(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun requireBcryptCompatiblePassword(password: String) {
        require(isBcryptCompatiblePassword(password)) { "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다." }
    }

    private fun isBcryptCompatiblePassword(password: String): Boolean =
        password.toByteArray(StandardCharsets.UTF_8).size <= BCRYPT_MAX_PASSWORD_BYTES

    private fun isLegacySha256Hash(password: String): Boolean = LEGACY_SHA256_PATTERN.matches(password)

    private fun requireNotSuspended(userId: Long) {
        val now = clock.instant()
        val suspended = userSanctionRepository.activeGlobalSanctionsForUser(userId)
            .any { sanction ->
                sanction.type == UserSanctionType.SUSPEND &&
                    (sanction.expiresAt == null || sanction.expiresAt.isAfter(now))
            }
        check(!suspended) { "정지된 사용자는 로그인할 수 없습니다." }
    }

    private fun userToDto(user: User): UserDto =
        UserDto(
            id = user.id,
            username = user.username,
            // 이거는 구현이 안되어 있다.
            displayName = user.displayName,
            profileImageUrl = user.profileImageUrl,
            //
            status = user.status,
            isActive = user.isActive,
            lastSeenAt = user.lastSeenAt,
            createdAt = user.createdAt,
        )
}

private data class PasswordVerification(
    val matched: Boolean,
    val requiresRehash: Boolean,
)

private const val BCRYPT_MAX_PASSWORD_BYTES = 72
private val LEGACY_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
