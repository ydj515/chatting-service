package com.chat.core.admin.port

import com.chat.core.dto.AdminCreateModerationRuleRequest
import com.chat.core.dto.AdminModerationRuleDto
import com.chat.core.dto.AdminUpdateModerationRuleRequest

interface ModerationRuleStore {
    fun listRules(roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto>

    fun create(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto

    fun update(ruleId: Long, request: AdminUpdateModerationRuleRequest): AdminModerationRuleDto

    fun disable(ruleId: Long): AdminModerationRuleDto
}
