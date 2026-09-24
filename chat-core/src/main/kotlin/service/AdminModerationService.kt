package com.chat.core.service

import com.chat.core.dto.AdminCreateModerationRuleRequest
import com.chat.core.dto.AdminCreateUserSanctionRequest
import com.chat.core.dto.AdminModerationRuleDto
import com.chat.core.dto.AdminUpdateModerationRuleRequest
import com.chat.core.dto.AdminUserSanctionDto

interface AdminModerationService {
    fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto>

    fun createRule(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto

    fun updateRule(actor: String, ruleId: Long, request: AdminUpdateModerationRuleRequest): AdminModerationRuleDto

    fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto

    fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto>

    fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto

    fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto
}
