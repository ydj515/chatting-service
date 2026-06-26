package com.chat.persistence.repository

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant

class UserSanctionJdbcRepositoryTest {

    @Test
    fun `activeSanctionsForUser는 room scoped active sanction만 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = UserSanctionJdbcRepository(jdbcTemplate)

        `when`(
            jdbcTemplate.query(
                any(String::class.java),
                any<RowMapper<UserSanctionRecord>>(),
                eq(7L),
                eq(10L),
            ),
        ).thenReturn(listOf(sanction(id = 2L, type = UserSanctionType.MUTE)))

        val sanctions = repository.activeSanctionsForUser(
            roomId = 10L,
            userId = 7L,
        )

        assertEquals(listOf(UserSanctionType.MUTE), sanctions.map { it.type })
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            any<RowMapper<UserSanctionRecord>>(),
            eq(7L),
            eq(10L),
        )
        assertFalse(sqlCaptor.value.contains("expires_at > ?"))
    }

    private fun sanction(
        id: Long,
        type: UserSanctionType,
    ): UserSanctionRecord {
        return UserSanctionRecord(
            id = id,
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
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
}
