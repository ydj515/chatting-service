package com.chat.core.user.port

import com.chat.domain.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

interface UserStore {
    fun findById(userId: Long): User?

    fun findByUsername(username: String): User?

    fun existsByUsername(username: String): Boolean

    fun save(user: User): User

    fun searchUsers(query: String, pageable: Pageable): Page<User>

    fun updateLastSeenAt(userId: Long, lastSeenAt: LocalDateTime)

    fun updatePassword(userId: Long, password: String): Int
}
