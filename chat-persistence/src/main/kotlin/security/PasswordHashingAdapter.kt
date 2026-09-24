package com.chat.persistence.security

import com.chat.core.user.port.PasswordHashing
import com.chat.core.user.port.PasswordVerification
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
class PasswordHashingAdapter(private val passwordEncoder: PasswordEncoder) : PasswordHashing {
    override fun encode(password: String): String {
        requireBcryptCompatiblePassword(password)
        return passwordEncoder.encode(password)
    }

    override fun verify(password: String, storedPassword: String): PasswordVerification {
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

    private companion object {
        const val BCRYPT_MAX_PASSWORD_BYTES = 72
        val LEGACY_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
