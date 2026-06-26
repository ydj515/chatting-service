package com.chat.persistence.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.service.AdminModerationService
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminModerationServiceImpl(
    private val ruleRepository: ModerationRuleJdbcRepository,
    private val sanctionRepository: UserSanctionJdbcRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val objectMapper: ObjectMapper,
) : AdminModerationService {

    override fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto> {
        return ruleRepository.listRules(roomId, enabled).map { it.toDto() }
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun createRule(
        actor: String,
        request: AdminCreateModerationRuleRequest,
    ): AdminModerationRuleDto {
        validateRuleRequest(request.scopeType, request.roomId, request.pattern)
        val record = ruleRepository.create(actor, request)
        audit(actor, "ADMIN_MODERATION_RULE_CREATED", "MODERATION_RULE", "rule:${record.id}", request)
        return record.toDto()
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun updateRule(
        actor: String,
        ruleId: Long,
        request: AdminUpdateModerationRuleRequest,
    ): AdminModerationRuleDto {
        if (request.pattern?.isBlank() == true) {
            throw IllegalArgumentException("pattern must not be blank")
        }

        val record = ruleRepository.update(ruleId, request)
        audit(actor, "ADMIN_MODERATION_RULE_UPDATED", "MODERATION_RULE", "rule:${record.id}", request)
        return record.toDto()
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto {
        val record = ruleRepository.disable(ruleId)
        audit(
            actor,
            "ADMIN_MODERATION_RULE_DISABLED",
            "MODERATION_RULE",
            "rule:${record.id}",
            mapOf("ruleId" to ruleId),
        )
        return record.toDto()
    }

    override fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto> {
        return sanctionRepository.listSanctions(roomId, userId, active).map { it.toDto() }
    }

    @Transactional
    @CacheEvict(
        value = ["userSanctions"],
        key = "T(java.lang.String).valueOf(#request.roomId).concat(':').concat(T(java.lang.String).valueOf(#request.userId))",
    )
    override fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto {
        validateSanctionRequest(request)
        val record = sanctionRepository.create(actor, request)
        audit(actor, "ADMIN_USER_SANCTION_CREATED", "USER_SANCTION", "sanction:${record.id}", request)
        return record.toDto()
    }

    @Transactional
    @CacheEvict(value = ["userSanctions"], allEntries = true)
    override fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto {
        val record = sanctionRepository.revoke(actor, sanctionId)
        audit(
            actor,
            "ADMIN_USER_SANCTION_REVOKED",
            "USER_SANCTION",
            "sanction:${record.id}",
            mapOf("sanctionId" to sanctionId),
        )
        return record.toDto()
    }

    private fun validateRuleRequest(scopeType: ModerationScopeType, roomId: Long?, pattern: String) {
        if (scopeType == ModerationScopeType.GLOBAL && roomId != null) {
            throw IllegalArgumentException("GLOBAL rule must not have roomId")
        }
        if (scopeType == ModerationScopeType.ROOM && roomId == null) {
            throw IllegalArgumentException("ROOM rule requires roomId")
        }
        if (pattern.isBlank()) {
            throw IllegalArgumentException("pattern must not be blank")
        }
    }

    private fun validateSanctionRequest(request: AdminCreateUserSanctionRequest) {
        if (request.type == UserSanctionType.SUSPEND_RESERVED) {
            throw IllegalArgumentException("SUSPEND_RESERVED is reserved for Phase 8.6")
        }
        if (request.scopeType != ModerationScopeType.ROOM || request.roomId == null) {
            throw IllegalArgumentException("Phase 8.5 supports ROOM scoped sanctions only")
        }
    }

    private fun audit(actor: String, action: String, targetType: String, targetId: String, metadata: Any) {
        auditLogRepository.record(
            actor = actor,
            action = action,
            targetType = targetType,
            targetId = targetId,
            metadataJson = objectMapper.writeValueAsString(metadata),
        )
    }
}
