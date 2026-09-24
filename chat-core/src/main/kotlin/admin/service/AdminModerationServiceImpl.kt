package com.chat.core.admin.service

import com.chat.core.admin.port.AdminAudit
import com.chat.core.admin.port.AdminSanctionStore
import com.chat.core.admin.port.ModerationRuleStore
import com.chat.core.admin.port.SanctionCacheInvalidation
import com.chat.core.admin.port.SuspendedSessions
import com.chat.core.dto.AdminCreateModerationRuleRequest
import com.chat.core.dto.AdminCreateUserSanctionRequest
import com.chat.core.dto.AdminModerationRuleDto
import com.chat.core.dto.AdminUpdateModerationRuleRequest
import com.chat.core.dto.AdminUserSanctionDto
import com.chat.core.dto.ModerationScopeType
import com.chat.core.dto.UserSanctionType
import com.chat.core.service.AdminModerationService
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AdminModerationServiceImpl(
    private val ruleRepository: ModerationRuleStore,
    private val sanctionRepository: AdminSanctionStore,
    private val auditRecorder: AdminAudit,
    private val suspendedSessions: SuspendedSessions,
    private val clock: Clock,
    private val sanctionCacheInvalidator: SanctionCacheInvalidation,
) : AdminModerationService {
    @Transactional(readOnly = true)
    override fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto> = ruleRepository.listRules(roomId, enabled)

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun createRule(
        actor: String,
        request: AdminCreateModerationRuleRequest,
    ): AdminModerationRuleDto {
        validateRuleRequest(request.scopeType, request.roomId, request.pattern)
        val record = ruleRepository.create(actor, request)
        auditRecorder.record(actor, "ADMIN_MODERATION_RULE_CREATED", "MODERATION_RULE", "rule:${record.id}", request)
        return record
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun updateRule(
        actor: String,
        ruleId: Long,
        request: AdminUpdateModerationRuleRequest,
    ): AdminModerationRuleDto {
        require(request.pattern?.isBlank() != true) { "pattern must not be blank" }

        val record = ruleRepository.update(ruleId, request)
        auditRecorder.record(actor, "ADMIN_MODERATION_RULE_UPDATED", "MODERATION_RULE", "rule:${record.id}", request)
        return record
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto {
        val record = ruleRepository.disable(ruleId)
        auditRecorder.record(
            actor,
            "ADMIN_MODERATION_RULE_DISABLED",
            "MODERATION_RULE",
            "rule:${record.id}",
            mapOf("ruleId" to ruleId),
        )
        return record
    }

    @Transactional(readOnly = true)
    override fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto> = sanctionRepository.listSanctions(roomId, userId, active)

    @Transactional
    override fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto {
        validateSanctionRequest(request)
        val record = sanctionRepository.create(actor, request)
        auditRecorder.record(actor, "ADMIN_USER_SANCTION_CREATED", "USER_SANCTION", "sanction:${record.id}", request)
        sanctionCacheInvalidator.enqueue(record.scopeType, record.roomId, record.userId)
        if (record.type == UserSanctionType.SUSPEND) {
            suspendedSessions.revokeAfterCommit(record.userId)
        }
        return record
    }

    @Transactional
    override fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto {
        val record = sanctionRepository.revoke(actor, sanctionId)
        auditRecorder.record(
            actor,
            "ADMIN_USER_SANCTION_REVOKED",
            "USER_SANCTION",
            "sanction:${record.id}",
            mapOf("sanctionId" to sanctionId),
        )
        sanctionCacheInvalidator.enqueue(record.scopeType, record.roomId, record.userId)
        return record
    }

    private fun validateRuleRequest(scopeType: ModerationScopeType, roomId: Long?, pattern: String) {
        require(scopeType != ModerationScopeType.GLOBAL || roomId == null) { "GLOBAL rule must not have roomId" }
        require(scopeType != ModerationScopeType.ROOM || roomId != null) { "ROOM rule requires roomId" }
        require(pattern.isNotBlank()) { "pattern must not be blank" }
    }

    private fun validateSanctionRequest(request: AdminCreateUserSanctionRequest) {
        when (request.type) {
            UserSanctionType.MUTE, UserSanctionType.BAN -> {
                require(request.scopeType == ModerationScopeType.ROOM && request.roomId != null) {
                    "MUTE and BAN require ROOM scope"
                }
            }
            UserSanctionType.SUSPEND -> {
                require(request.scopeType == ModerationScopeType.GLOBAL && request.roomId == null) {
                    "SUSPEND requires GLOBAL scope"
                }
            }
        }
        // 만료 시각이 과거/현재면 send 경로(activeSanctionsForUser)에서 절대 적용되지 않으므로 거부한다.
        val expiresAt = request.expiresAt
        require(expiresAt == null || expiresAt.isAfter(clock.instant())) { "expiresAt must be in the future" }
    }
}
