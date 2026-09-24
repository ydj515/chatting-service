package com.chat.persistence.repository

import com.chat.core.admin.port.ModerationRuleStore
import com.chat.core.dto.AdminCreateModerationRuleRequest
import com.chat.core.dto.AdminModerationRuleDto
import com.chat.core.dto.AdminUpdateModerationRuleRequest
import org.springframework.stereotype.Repository

@Repository
class ModerationRuleStoreAdapter(private val repository: ModerationRuleJdbcRepository) : ModerationRuleStore {
    override fun listRules(roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto> = repository.listRules(roomId, enabled).map { it.toDto() }

    override fun create(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto = repository.create(actor, request).toDto()

    override fun update(ruleId: Long, request: AdminUpdateModerationRuleRequest): AdminModerationRuleDto = repository.update(ruleId, request).toDto()

    override fun disable(ruleId: Long): AdminModerationRuleDto = repository.disable(ruleId).toDto()
}
