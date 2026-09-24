package com.chat.persistence.repository

import com.chat.core.admin.port.AdminSanctionStore
import com.chat.core.dto.AdminCreateUserSanctionRequest
import com.chat.core.dto.AdminUserSanctionDto
import org.springframework.stereotype.Repository

@Repository
class AdminSanctionStoreAdapter(private val repository: UserSanctionJdbcRepository) : AdminSanctionStore {
    override fun listSanctions(roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto> = repository.listSanctions(roomId, userId, active).map { it.toDto() }

    override fun create(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto = repository.create(actor, request).toDto()

    override fun revoke(actor: String, sanctionId: Long): AdminUserSanctionDto = repository.revoke(actor, sanctionId).toDto()
}
