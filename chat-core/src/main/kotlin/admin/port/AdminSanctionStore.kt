package com.chat.core.admin.port

import com.chat.core.dto.AdminCreateUserSanctionRequest
import com.chat.core.dto.AdminUserSanctionDto

interface AdminSanctionStore {
    fun listSanctions(roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto>

    fun create(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto

    fun revoke(actor: String, sanctionId: Long): AdminUserSanctionDto
}
