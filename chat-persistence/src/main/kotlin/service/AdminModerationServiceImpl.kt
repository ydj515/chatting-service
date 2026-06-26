package com.chat.persistence.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.service.AdminModerationService
import com.chat.domain.service.SessionControlPublisher
import com.chat.domain.service.SessionTokenService
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Service
class AdminModerationServiceImpl(
    private val ruleRepository: ModerationRuleJdbcRepository,
    private val sanctionRepository: UserSanctionJdbcRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val objectMapper: ObjectMapper,
    private val sessionTokenService: SessionTokenService,
    private val sessionControlPublisher: SessionControlPublisher,
    private val cacheManager: CacheManager,
) : AdminModerationService {

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    override fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto> {
        return sanctionRepository.listSanctions(roomId, userId, active).map { it.toDto() }
    }

    @Transactional
    override fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto {
        validateSanctionRequest(request)
        val record = sanctionRepository.create(actor, request)
        audit(actor, "ADMIN_USER_SANCTION_CREATED", "USER_SANCTION", "sanction:${record.id}", request)
        evictUserSanctionCache(record)
        if (record.type == UserSanctionType.SUSPEND) {
            afterCommit {
                sessionTokenService.revokeUserTokens(record.userId)
                sessionControlPublisher.forceLogoutUser(record.userId, "suspended")
            }
        }
        return record.toDto()
    }

    @Transactional
    override fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto {
        val record = sanctionRepository.revoke(actor, sanctionId)
        audit(
            actor,
            "ADMIN_USER_SANCTION_REVOKED",
            "USER_SANCTION",
            "sanction:${record.id}",
            mapOf("sanctionId" to sanctionId),
        )
        evictUserSanctionCache(record)
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
        when (request.type) {
            UserSanctionType.MUTE, UserSanctionType.BAN -> {
                if (request.scopeType != ModerationScopeType.ROOM || request.roomId == null) {
                    throw IllegalArgumentException("MUTE and BAN require ROOM scope")
                }
            }
            UserSanctionType.SUSPEND -> {
                if (request.scopeType != ModerationScopeType.GLOBAL || request.roomId != null) {
                    throw IllegalArgumentException("SUSPEND requires GLOBAL scope")
                }
            }
        }
        // 만료 시각이 과거/현재면 send 경로(activeSanctionsForUser)에서 절대 적용되지 않으므로 거부한다.
        val expiresAt = request.expiresAt
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw IllegalArgumentException("expiresAt must be in the future")
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

    private fun evictUserSanctionCache(record: UserSanctionRecord) {
        val cache = cacheManager.getCache(USER_SANCTIONS_CACHE) ?: return
        when (record.scopeType) {
            ModerationScopeType.GLOBAL -> cache.evict("global:${record.userId}")
            ModerationScopeType.ROOM -> record.roomId?.let { roomId -> cache.evict("$roomId:${record.userId}") }
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action()
                }
            },
        )
    }

    private companion object {
        const val USER_SANCTIONS_CACHE = "userSanctions"
    }
}
