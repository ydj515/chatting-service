package com.chat.persistence.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
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
import org.springframework.transaction.annotation.Transactional
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
    fun `createSanction은 SUSPEND_RESERVED 생성을 거부한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.GLOBAL,
            userId = 7L,
            type = UserSanctionType.SUSPEND_RESERVED,
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.createSanction("admin-local", request)
        }

        verifyNoInteractions(fixture.sanctionRepository)
    }

    private fun fixture(): Fixture {
        val ruleRepository = mock(ModerationRuleJdbcRepository::class.java)
        val sanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val auditRepository = mock(AdminAuditLogRepository::class.java)
        return Fixture(
            service = AdminModerationServiceImpl(
                ruleRepository = ruleRepository,
                sanctionRepository = sanctionRepository,
                auditLogRepository = auditRepository,
                objectMapper = jacksonObjectMapper(),
            ),
            ruleRepository = ruleRepository,
            sanctionRepository = sanctionRepository,
            auditRepository = auditRepository,
        )
    }

    private data class Fixture(
        val service: AdminModerationServiceImpl,
        val ruleRepository: ModerationRuleJdbcRepository,
        val sanctionRepository: UserSanctionJdbcRepository,
        val auditRepository: AdminAuditLogRepository,
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

    private fun sanctionRecord(): UserSanctionRecord {
        return UserSanctionRecord(
            id = 2L,
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = UserSanctionType.MUTE,
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
