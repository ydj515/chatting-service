package com.chat.core.user.port

import com.chat.core.dto.UserSanctionType
import java.time.Instant

interface LoginSanctionReader {
    fun activeGlobalSanctionsForUser(userId: Long): List<LoginSanction>
}

data class LoginSanction(val type: UserSanctionType, val expiresAt: Instant?)
