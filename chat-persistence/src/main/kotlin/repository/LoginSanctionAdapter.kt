package com.chat.persistence.repository

import com.chat.core.user.port.LoginSanction
import com.chat.core.user.port.LoginSanctionReader
import org.springframework.stereotype.Repository

@Repository
class LoginSanctionAdapter(private val repository: UserSanctionJdbcRepository) : LoginSanctionReader {
    override fun activeGlobalSanctionsForUser(userId: Long): List<LoginSanction> =
        repository.activeGlobalSanctionsForUser(userId).map { LoginSanction(it.type, it.expiresAt) }
}
