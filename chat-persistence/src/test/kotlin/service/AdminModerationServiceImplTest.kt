package com.chat.persistence.service

import com.chat.core.admin.service.AdminModerationServiceImpl
import com.chat.core.dto.AdminCreateModerationRuleRequest
import com.chat.core.dto.AdminCreateUserSanctionRequest
import com.chat.core.dto.ModerationAction
import com.chat.core.dto.ModerationMatchType
import com.chat.core.dto.ModerationScopeType
import com.chat.core.dto.UserSanctionType
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.AdminSanctionStoreAdapter
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.ModerationRuleRecord
import com.chat.persistence.repository.ModerationRuleStoreAdapter
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

class AdminModerationServiceImplTest {
    @Test
    fun `sanction expiration is validated with the injected clock`() {
        val now = Instant.parse("2099-01-01T00:00:00Z")
        val fixture = fixture(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        val request = AdminCreateUserSanctionRequest(scopeType = ModerationScopeType.ROOM, roomId = 10, userId = 7, type = UserSanctionType.MUTE, expiresAt = now)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { fixture.service.createSanction("admin-local", request) }
        org.mockito.Mockito.verifyNoInteractions(fixture.sanctionRepository, fixture.invalidator)
    }

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
    fun `createSanction은 cache invalidation 작업을 등록한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = UserSanctionType.MUTE,
        )
        `when`(fixture.sanctionRepository.create("admin-local", request)).thenReturn(sanctionRecord())

        fixture.service.createSanction("admin-local", request)

        verify(fixture.invalidator).enqueue(request.scopeType, request.roomId, request.userId)
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

        val response = fixture.service.createSanction("admin-local", request)
        assertEquals(UserSanctionType.SUSPEND, response.type)
        verify(fixture.revoker).revokeAfterCommit(7L)
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
        verifyNoInteractions(fixture.revoker)
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
        verifyNoInteractions(fixture.revoker)
    }

    @ParameterizedTest
    @CsvSource("ROOM,false", "ROOM,true", "GLOBAL,false", "GLOBAL,true")
    fun `sanction changes always enqueue invalidation`(scope: ModerationScopeType, revoke: Boolean) {
        val fixture = fixture()
        val roomId = if (scope == ModerationScopeType.ROOM) 10L else null
        val type = if (scope == ModerationScopeType.ROOM) UserSanctionType.MUTE else UserSanctionType.SUSPEND
        val record = sanctionRecord(scope, roomId, type)
        val request = AdminCreateUserSanctionRequest(scopeType = scope, roomId = roomId, userId = 7L, type = type)
        `when`(fixture.sanctionRepository.create("admin-local", request)).thenReturn(record)
        `when`(fixture.sanctionRepository.revoke("admin-local", record.id)).thenReturn(record)
        if (revoke) fixture.service.revokeSanction("admin-local", record.id) else fixture.service.createSanction("admin-local", request)
        verify(fixture.invalidator).enqueue(record.scopeType, record.roomId, record.userId)
    }

    private fun fixture(clock: java.time.Clock = java.time.Clock.systemUTC()): Fixture {
        val ruleRepository = mock(ModerationRuleJdbcRepository::class.java)
        val sanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val auditRepository = mock(AdminAuditLogRepository::class.java)
        val revoker = mock(SuspendedSessionRevoker::class.java)
        val invalidator = mock(SanctionCacheInvalidator::class.java)
        return Fixture(
            service = AdminModerationServiceImpl(
                ruleRepository = ModerationRuleStoreAdapter(ruleRepository),
                sanctionRepository = AdminSanctionStoreAdapter(sanctionRepository),
                auditRecorder = AdminAuditRecorder(auditRepository, jacksonObjectMapper()),
                suspendedSessions = revoker,
                clock = clock,
                sanctionCacheInvalidator = invalidator,
            ),
            ruleRepository = ruleRepository,
            sanctionRepository = sanctionRepository,
            auditRepository = auditRepository,
            revoker = revoker,
            invalidator = invalidator,
        )
    }

    private data class Fixture(
        val service: AdminModerationServiceImpl,
        val ruleRepository: ModerationRuleJdbcRepository,
        val sanctionRepository: UserSanctionJdbcRepository,
        val auditRepository: AdminAuditLogRepository,
        val revoker: SuspendedSessionRevoker,
        val invalidator: SanctionCacheInvalidator,
    )

    private fun ruleRecord(): ModerationRuleRecord =
        ModerationRuleRecord(
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

    private fun sanctionRecord(
        scopeType: ModerationScopeType = ModerationScopeType.ROOM,
        roomId: Long? = 10L,
        type: UserSanctionType = UserSanctionType.MUTE,
    ): UserSanctionRecord =
        UserSanctionRecord(
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
