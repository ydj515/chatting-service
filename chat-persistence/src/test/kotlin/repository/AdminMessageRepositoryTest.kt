package com.chat.persistence.repository

import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageSearchMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.LocalDateTime

class AdminMessageRepositoryTest {

    @Test
    fun `history query는 roomId와 created_at 범위와 cursor를 parameter로 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.query(
                anyString(),
                anyAdminMessageRowMapper(),
                anyVararg(),
            ),
        ).thenReturn(emptyList())

        repository.findRoomMessages(
            roomId = 10L,
            from = LocalDateTime.parse("2026-06-14T00:00:00"),
            to = LocalDateTime.parse("2026-06-15T00:00:00"),
            cursor = 100L,
            limit = 50,
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            anyAdminMessageRowMapper(),
            eq(10L),
            eq(Timestamp.valueOf(LocalDateTime.parse("2026-06-14T00:00:00"))),
            eq(Timestamp.valueOf(LocalDateTime.parse("2026-06-15T00:00:00"))),
            eq(100L),
            eq(50),
        )
        assertTrue(sqlCaptor.value.contains("FROM chat_messages cm"))
        assertTrue(sqlCaptor.value.contains("cm.room_id = ?"))
        assertTrue(sqlCaptor.value.contains("cm.created_at >= ?"))
        assertTrue(sqlCaptor.value.contains("cm.created_at < ?"))
        assertTrue(sqlCaptor.value.contains("cm.room_seq < ?"))
        assertTrue(sqlCaptor.value.contains("ORDER BY cm.room_seq DESC"))
    }

    @Test
    fun `search query 기본 모드는 FTS 필터만 parameter로 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.query(
                anyString(),
                anyAdminMessageRowMapper(),
                anyVararg(),
            ),
        ).thenReturn(emptyList())

        repository.searchMessages(
            query = "hello",
            searchMode = AdminMessageSearchMode.FTS,
            roomId = 10L,
            from = null,
            to = null,
            senderId = 7L,
            cursor = null,
            limit = 25,
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            anyAdminMessageRowMapper(),
            eq("hello"),
            eq(10L),
            eq(7L),
            eq(25),
        )
        assertTrue(sqlCaptor.value.contains("plainto_tsquery('simple', ?)"))
        assertTrue(!sqlCaptor.value.contains(" OR "))
        assertTrue(!sqlCaptor.value.contains("cm.content ILIKE ?"))
        assertTrue(sqlCaptor.value.contains("cm.room_id = ?"))
        assertTrue(sqlCaptor.value.contains("cm.sender_id = ?"))
    }

    @Test
    fun `search query CONTAINS 모드는 trigram fallback 필터를 parameter로 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.query(
                anyString(),
                anyAdminMessageRowMapper(),
                anyVararg(),
            ),
        ).thenReturn(emptyList())

        repository.searchMessages(
            query = "hello",
            searchMode = AdminMessageSearchMode.CONTAINS,
            roomId = 10L,
            from = null,
            to = null,
            senderId = null,
            cursor = null,
            limit = 25,
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            anyAdminMessageRowMapper(),
            eq("%hello%"),
            eq(10L),
            eq(25),
        )
        assertTrue(sqlCaptor.value.contains("cm.content ILIKE ?"))
        assertTrue(!sqlCaptor.value.contains("plainto_tsquery('simple', ?)"))
    }

    @Test
    fun `room status는 room_storage_configs 기준 정책 값을 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                anyRoomStatusRowMapper(),
                eq(10L),
            ),
        ).thenReturn(
            com.chat.domain.dto.AdminRoomStatusDto(
                roomId = 10L,
                heatLevel = "HOT",
                liveFeedMaxMessages = 1000,
                liveFeedMaxAgeSeconds = 60,
                rateLimitPerSecond = null,
                slowModeSeconds = null,
                replicaLagMs = 5,
                searchP95LatencyMs = null,
            ),
        )

        val status = repository.findRoomStatus(10L)

        assertEquals("HOT", status.heatLevel)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyRoomStatusRowMapper(),
            eq(10L),
        )
        assertTrue(sqlCaptor.value.contains("room_storage_configs"))
    }

    private fun anyAdminMessageRowMapper(): RowMapper<AdminMessageDto> {
        any(RowMapper::class.java)
        return uninitialized()
    }

    private fun anyRoomStatusRowMapper(): RowMapper<com.chat.domain.dto.AdminRoomStatusDto> {
        any(RowMapper::class.java)
        return uninitialized()
    }

    private fun anyVararg(): Array<Any> {
        any<Array<Any>>()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
