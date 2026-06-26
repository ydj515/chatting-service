package com.chat.persistence.repository

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant

class ModerationRuleJdbcRepositoryTest {

    @Test
    fun `activeRulesForRoom은 global과 room rule을 함께 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = ModerationRuleJdbcRepository(jdbcTemplate)

        `when`(
            jdbcTemplate.query(
                any(String::class.java),
                any<RowMapper<ModerationRuleRecord>>(),
                eq(10L),
            ),
        ).thenReturn(listOf(rule(id = 1L, scopeType = ModerationScopeType.GLOBAL)))

        val rules = repository.activeRulesForRoom(10L)

        assertEquals(listOf(1L), rules.map { it.id })
        verify(jdbcTemplate).query(
            contains("scope_type = 'GLOBAL'"),
            any<RowMapper<ModerationRuleRecord>>(),
            eq(10L),
        )
    }

    private fun rule(
        id: Long,
        scopeType: ModerationScopeType,
    ): ModerationRuleRecord {
        return ModerationRuleRecord(
            id = id,
            scopeType = scopeType,
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
}
