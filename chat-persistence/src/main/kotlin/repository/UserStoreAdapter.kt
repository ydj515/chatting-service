package com.chat.persistence.repository

import com.chat.core.user.port.UserStore
import com.chat.domain.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class UserStoreAdapter(private val repository: UserRepository) : UserStore {
    override fun findById(userId: Long): User? = repository.findById(userId).orElse(null)

    override fun findByUsername(username: String): User? = repository.findByUsername(username)

    override fun existsByUsername(username: String): Boolean = repository.existsByUsername(username)

    override fun save(user: User): User = repository.save(user)

    override fun searchUsers(query: String, pageable: Pageable): Page<User> = repository.searchUsers(query, pageable)

    override fun updateLastSeenAt(userId: Long, lastSeenAt: LocalDateTime) = repository.updateLastSeenAt(userId, lastSeenAt)

    override fun updatePassword(userId: Long, password: String): Int = repository.updatePassword(userId, password)
}
