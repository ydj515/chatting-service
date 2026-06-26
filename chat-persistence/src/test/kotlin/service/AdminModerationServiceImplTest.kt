package com.chat.persistence.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.service.SessionControlPublisher
import com.chat.domain.service.SessionTokenService
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.ModerationRuleRecord
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

class AdminModerationServiceImplTest {

    @Test
    fun `createRule은 repository 저장과 audit log를 transaction으로 묶는다`() {
        val method = AdminModerationServiceImpl::class.java.getMethod(
            "createRule",
            String::class.java,
            AdminCreateModerationRuleRequest::class.java,
        )

        assertNotNull(method.getAnnotation(Transactional::class.java))
    }

    @Test
    fun `listRules와 listSanctions는 readOnly transaction으로 조회한다`() {
        val listRulesMethod = AdminModerationServiceImpl::class.java.getMethod(
            "listRules",
            String::class.java,
            java.lang.Long::class.java,
            java.lang.Boolean::class.java,
        )
        val listSanctionsMethod = AdminModerationServiceImpl::class.java.getMethod(
            "listSanctions",
            String::class.java,
            java.lang.Long::class.java,
            java.lang.Long::class.java,
            java.lang.Boolean::class.java,
        )

        assertEquals(true, listRulesMethod.getAnnotation(Transactional::class.java)?.readOnly)
        assertEquals(true, listSanctionsMethod.getAnnotation(Transactional::class.java)?.readOnly)
    }

    @Test
    fun `createSanction은 대상 user sanction cache key를 evict한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = UserSanctionType.MUTE,
        )
        `when`(fixture.sanctionRepository.create("admin-local", request)).thenReturn(sanctionRecord())

        fixture.service.createSanction("admin-local", request)

        verify(fixture.userSanctionsCache).evict("10:7")
    }

    @Test
    fun `createRule은 rule을 저장하고 audit log를 남긴다`() {
        val fixture = fixture()
        val request = AdminCreateModerationRuleRequest(
            scopeType = ModerationScopeType.GLOBAL,
            pattern = "blocked",
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
        )
        `when`(fixture.ruleRepository.create("admin-local", request)).thenReturn(ruleRecord())

        val response = fixture.service.createRule("admin-local", request)

        assertEquals(1L, response.id)
        verify(fixture.auditRepository).record(
            eqString("admin-local"),
            eqString("ADMIN_MODERATION_RULE_CREATED"),
            eqString("MODERATION_RULE"),
            eqString("rule:1"),
            containsString("blocked"),
        )
    }

    @Test
    fun `global rule은 roomId를 가질 수 없다`() {
        val fixture = fixture()
        val request = AdminCreateModerationRuleRequest(
            scopeType = ModerationScopeType.GLOBAL,
            roomId = 10L,
            pattern = "blocked",
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.createRule("admin-local", request)
        }

        verifyNoInteractions(fixture.ruleRepository)
    }

    @Test
    fun `createSanction은 room scoped mute를 저장하고 audit log를 남긴다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = UserSanctionType.MUTE,
            reason = "spam",
        )
        `when`(fixture.sanctionRepository.create("admin-local", request)).thenReturn(sanctionRecord())

        val response = fixture.service.createSanction("admin-local", request)

        assertEquals(2L, response.id)
        verify(fixture.auditRepository).record(
            eqString("admin-local"),
            eqString("ADMIN_USER_SANCTION_CREATED"),
            eqString("USER_SANCTION"),
            eqString("sanction:2"),
            containsString("spam"),
        )
    }

    @Test
    fun `createSanction은 global suspend를 저장하고 token revoke와 force logout을 요청한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.GLOBAL,
            userId = 7L,
            type = UserSanctionType.SUSPEND,
            reason = "abuse",
        )
        `when`(fixture.sanctionRepository.create("admin-local", request)).thenReturn(
            sanctionRecord(
                scopeType = ModerationScopeType.GLOBAL,
                roomId = null,
                type = UserSanctionType.SUSPEND,
            ),
        )

        TransactionSynchronizationManager.initSynchronization()
        try {
            val response = fixture.service.createSanction("admin-local", request)

            assertEquals(UserSanctionType.SUSPEND, response.type)
            verifyNoInteractions(fixture.sessionTokenService, fixture.sessionControlPublisher)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }
        verify(fixture.sessionTokenService).revokeUserTokens(7L)
        verify(fixture.sessionControlPublisher).forceLogoutUser(7L, "suspended")
    }

    @Test
    fun `room scoped suspend는 거부한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = UserSanctionType.SUSPEND,
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.createSanction("admin-local", request)
        }

        verifyNoInteractions(fixture.sanctionRepository)
        verifyNoInteractions(fixture.sessionTokenService, fixture.sessionControlPublisher)
    }

    @Test
    fun `global mute는 거부한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.GLOBAL,
            userId = 7L,
            type = UserSanctionType.MUTE,
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.createSanction("admin-local", request)
        }

        verifyNoInteractions(fixture.sanctionRepository)
        verifyNoInteractions(fixture.sessionTokenService, fixture.sessionControlPublisher)
    }

    private fun fixture(): Fixture {
        val ruleRepository = mock(ModerationRuleJdbcRepository::class.java)
        val sanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val auditRepository = mock(AdminAuditLogRepository::class.java)
        val sessionTokenService = mock(SessionTokenService::class.java)
        val sessionControlPublisher = mock(SessionControlPublisher::class.java)
        val cacheManager = mock(CacheManager::class.java)
        val userSanctionsCache = mock(Cache::class.java)
        `when`(cacheManager.getCache("userSanctions")).thenReturn(userSanctionsCache)
        return Fixture(
            service = AdminModerationServiceImpl(
                ruleRepository = ruleRepository,
                sanctionRepository = sanctionRepository,
                auditLogRepository = auditRepository,
                objectMapper = jacksonObjectMapper(),
                sessionTokenService = sessionTokenService,
                sessionControlPublisher = sessionControlPublisher,
                cacheManager = cacheManager,
            ),
            ruleRepository = ruleRepository,
            sanctionRepository = sanctionRepository,
            auditRepository = auditRepository,
            sessionTokenService = sessionTokenService,
            sessionControlPublisher = sessionControlPublisher,
            userSanctionsCache = userSanctionsCache,
        )
    }

    private data class Fixture(
        val service: AdminModerationServiceImpl,
        val ruleRepository: ModerationRuleJdbcRepository,
        val sanctionRepository: UserSanctionJdbcRepository,
        val auditRepository: AdminAuditLogRepository,
        val sessionTokenService: SessionTokenService,
        val sessionControlPublisher: SessionControlPublisher,
        val userSanctionsCache: Cache,
    )

    private fun ruleRecord(): ModerationRuleRecord {
        return ModerationRuleRecord(
            id = 1L,
            scopeType = ModerationScopeType.GLOBAL,
            roomId = null,
            pattern = "blocked",
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
            enabled = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
        )
    }

    private fun sanctionRecord(
        scopeType: ModerationScopeType = ModerationScopeType.ROOM,
        roomId: Long? = 10L,
        type: UserSanctionType = UserSanctionType.MUTE,
    ): UserSanctionRecord {
        return UserSanctionRecord(
            id = 2L,
            scopeType = scopeType,
            roomId = roomId,
            userId = 7L,
            type = type,
            reason = "spam",
            expiresAt = null,
            active = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            revokedBy = null,
            revokedAt = null,
        )
    }

    private fun eqString(value: String): String {
        eq(value)
        return uninitialized()
    }

    private fun containsString(value: String): String {
        contains(value)
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
