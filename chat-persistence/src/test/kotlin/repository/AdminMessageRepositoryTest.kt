package com.chat.persistence.repository

import com.chat.domain.dto.AdminMessageCursor
import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminRoomPolicyUpdateRequest
import com.chat.domain.dto.AdminRoomStatusDto
import com.chat.domain.dto.AdminMessageSearchCursor
import com.chat.domain.dto.AdminMessageSearchMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant

class AdminMessageRepositoryTest {

    @Test
    fun `history query는 roomId와 created_at 범위와 cursor를 parameter로 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        val cursor = AdminMessageCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01Z"),
            roomSeq = 100L,
            messageId = "msg-100",
        )
        `when`(
            jdbcTemplate.query(
                anyString(),
                anyAdminMessageRowMapper(),
                anyVararg(),
            ),
        ).thenReturn(emptyList())

        repository.findRoomMessages(
            roomId = 10L,
            from = Instant.parse("2026-06-14T00:00:00Z"),
            to = Instant.parse("2026-06-15T00:00:00Z"),
            cursor = cursor,
            limit = 50,
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            anyAdminMessageRowMapper(),
            eq(10L),
            eq(Timestamp.from(Instant.parse("2026-06-14T00:00:00Z"))),
            eq(Timestamp.from(Instant.parse("2026-06-15T00:00:00Z"))),
            eq(100L),
            eq(100L),
            eq(Timestamp.from(cursor.createdAt)),
            eq(100L),
            eq(Timestamp.from(cursor.createdAt)),
            eq("msg-100"),
            eq(50),
        )
        assertTrue(sqlCaptor.value.contains("FROM chat_messages cm"))
        assertTrue(sqlCaptor.value.contains("cm.room_id = ?"))
        assertTrue(sqlCaptor.value.contains("cm.created_at >= ?"))
        assertTrue(sqlCaptor.value.contains("cm.created_at < ?"))
        assertTrue(sqlCaptor.value.contains("cm.room_seq < ?"))
        assertTrue(sqlCaptor.value.contains("cm.room_seq = ? AND cm.created_at < ?"))
        assertTrue(sqlCaptor.value.contains("cm.room_seq = ? AND cm.created_at = ? AND cm.message_id < ?"))
        assertTrue(sqlCaptor.value.contains("ORDER BY cm.room_seq DESC, cm.created_at DESC, cm.message_id DESC"))
    }

    @Test
    fun `history query는 cursor가 없어도 안정적인 tuple ordering을 사용한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        `when`(
            jdbcTemplate.query(
                anyString(),
                anyAdminMessageRowMapper(),
                anyVararg(),
            ),
        ).thenReturn(emptyList())

        repository.findRoomMessages(
            roomId = 10L,
            from = null,
            to = null,
            cursor = null,
            limit = 50,
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            anyAdminMessageRowMapper(),
            eq(10L),
            eq(50),
        )
        assertTrue(sqlCaptor.value.contains("ORDER BY cm.room_seq DESC, cm.created_at DESC, cm.message_id DESC"))
    }

    @Test
    fun `search query는 빈 query일 때 room 필터만으로 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        `when`(
            jdbcTemplate.query(
                anyString(),
                anyAdminMessageRowMapper(),
                anyVararg(),
            ),
        ).thenReturn(emptyList())

        repository.searchMessages(
            query = "   ",
            searchMode = AdminMessageSearchMode.FTS,
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
            eq(10L),
            eq(25),
        )
        assertTrue(!sqlCaptor.value.contains("plainto_tsquery"))
        assertTrue(!sqlCaptor.value.contains("ILIKE"))
        assertTrue(sqlCaptor.value.contains("cm.room_id = ?"))
    }

    @Test
    fun `search query는 query와 필터가 모두 없으면 전체 scan을 피한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)

        val result = repository.searchMessages(
            query = "   ",
            searchMode = AdminMessageSearchMode.FTS,
            roomId = null,
            from = null,
            to = null,
            senderId = null,
            cursor = null,
            limit = 25,
        )

        assertEquals(emptyList<AdminMessageDto>(), result)
        verifyNoInteractions(jdbcTemplate)
    }

    @Test
    fun `search query 기본 모드는 FTS 필터만 parameter로 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
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
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
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
    fun `search query cursor는 정렬 tuple과 같은 key로 predicate를 만든다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        val cursor = AdminMessageSearchCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01Z"),
            roomSeq = 1001L,
            messageId = "msg-1001",
        )
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
            roomId = null,
            from = null,
            to = null,
            senderId = null,
            cursor = cursor,
            limit = 25,
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            anyAdminMessageRowMapper(),
            eq("hello"),
            eq(Timestamp.from(cursor.createdAt)),
            eq(Timestamp.from(cursor.createdAt)),
            eq(1001L),
            eq(Timestamp.from(cursor.createdAt)),
            eq(1001L),
            eq("msg-1001"),
            eq(25),
        )
        assertTrue(sqlCaptor.value.contains("cm.created_at < ?"))
        assertTrue(sqlCaptor.value.contains("cm.created_at = ? AND cm.room_seq < ?"))
        assertTrue(sqlCaptor.value.contains("cm.created_at = ? AND cm.room_seq = ? AND cm.message_id < ?"))
        assertTrue(sqlCaptor.value.contains("ORDER BY cm.created_at DESC, cm.room_seq DESC, cm.message_id DESC"))
    }

    @Test
    fun `room status는 room_storage_configs 기준 정책 값을 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
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

    @Test
    fun `room policy override는 room_storage_configs를 upsert하고 갱신된 status를 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        val expectedStatus = AdminRoomStatusDto(
            roomId = 10L,
            heatLevel = "VERY_HOT",
            liveFeedMaxMessages = 500,
            liveFeedMaxAgeSeconds = 30,
            rateLimitPerSecond = 1000,
            slowModeSeconds = 5,
            replicaLagMs = 0,
            searchP95LatencyMs = null,
            userRateLimitPerSecond = 2,
            autoPolicyEnabled = false,
        )
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                anyRoomStatusRowMapper(),
                eq(10L),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(1000),
                eq(2),
                eq(5),
                eq(false),
                isNull<Boolean>(),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(false),
                eq(1000),
                eq(false),
                eq(2),
                eq(false),
                eq(5),
                eq(false),
                isNull<Boolean>(),
            ),
        ).thenReturn(expectedStatus)

        val status = repository.updateRoomPolicy(
            roomId = 10L,
            request = AdminRoomPolicyUpdateRequest(
                heatLevel = "VERY_HOT",
                liveFeedMaxMessages = 500,
                liveFeedMaxAgeSeconds = 30,
                rateLimitPerSecond = 1000,
                userRateLimitPerSecond = 2,
                slowModeSeconds = 5,
            ),
        )

        assertEquals(expectedStatus, status)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyRoomStatusRowMapper(),
            eq(10L),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(1000),
            eq(2),
            eq(5),
            eq(false),
            isNull<Boolean>(),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(false),
            eq(1000),
            eq(false),
            eq(2),
            eq(false),
            eq(5),
            eq(false),
            isNull<Boolean>(),
        )
        assertTrue(sqlCaptor.value.contains("INSERT INTO room_storage_configs"))
        assertTrue(sqlCaptor.value.contains("ON CONFLICT (room_id) DO UPDATE"))
        assertTrue(sqlCaptor.value.contains("hot_room_policy = COALESCE(?, room_storage_configs.hot_room_policy)"))
        assertTrue(sqlCaptor.value.contains("slow_mode_seconds = CASE WHEN ? THEN NULL"))
        assertTrue(sqlCaptor.value.contains("RETURNING"))
    }

    @Test
    fun `room policy override는 기본적으로 자동 room policy 적용을 비활성화한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        val expectedStatus = AdminRoomStatusDto(
            roomId = 10L,
            heatLevel = "VERY_HOT",
            liveFeedMaxMessages = 500,
            liveFeedMaxAgeSeconds = 30,
            rateLimitPerSecond = 1000,
            slowModeSeconds = 5,
            replicaLagMs = 0,
            searchP95LatencyMs = null,
            userRateLimitPerSecond = 2,
            autoPolicyEnabled = false,
        )
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                anyRoomStatusRowMapper(),
                eq(10L),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(1000),
                eq(2),
                eq(5),
                eq(false),
                isNull<Boolean>(),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(false),
                eq(1000),
                eq(false),
                eq(2),
                eq(false),
                eq(5),
                eq(false),
                isNull<Boolean>(),
            ),
        ).thenReturn(expectedStatus)

        val status = repository.updateRoomPolicy(
            roomId = 10L,
            request = AdminRoomPolicyUpdateRequest(
                heatLevel = "VERY_HOT",
                liveFeedMaxMessages = 500,
                liveFeedMaxAgeSeconds = 30,
                rateLimitPerSecond = 1000,
                userRateLimitPerSecond = 2,
                slowModeSeconds = 5,
            ),
        )

        assertFalse(status.autoPolicyEnabled)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyRoomStatusRowMapper(),
            eq(10L),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(1000),
            eq(2),
            eq(5),
            eq(false),
            isNull<Boolean>(),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(false),
            eq(1000),
            eq(false),
            eq(2),
            eq(false),
            eq(5),
            eq(false),
            isNull<Boolean>(),
        )
        assertTrue(sqlCaptor.value.contains("auto_policy_enabled"))
    }

    @Test
    fun `room policy override는 명시적 clear flag로 admission 제한을 해제한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(jdbcTemplate = jdbcTemplate, writeJdbcTemplate = jdbcTemplate)
        val expectedStatus = AdminRoomStatusDto(
            roomId = 10L,
            heatLevel = "VERY_HOT",
            liveFeedMaxMessages = 500,
            liveFeedMaxAgeSeconds = 30,
            rateLimitPerSecond = null,
            slowModeSeconds = null,
            replicaLagMs = 0,
            searchP95LatencyMs = null,
            userRateLimitPerSecond = null,
            autoPolicyEnabled = false,
        )
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                anyRoomStatusRowMapper(),
                eq(10L),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                isNull<Int>(),
                isNull<Int>(),
                isNull<Int>(),
                eq(false),
                isNull<Boolean>(),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(true),
                isNull<Int>(),
                eq(true),
                isNull<Int>(),
                eq(true),
                isNull<Int>(),
                eq(false),
                isNull<Boolean>(),
            ),
        ).thenReturn(expectedStatus)

        val status = repository.updateRoomPolicy(
            roomId = 10L,
            request = AdminRoomPolicyUpdateRequest(
                heatLevel = "VERY_HOT",
                liveFeedMaxMessages = 500,
                liveFeedMaxAgeSeconds = 30,
                clearRateLimit = true,
                clearUserRateLimit = true,
                clearSlowMode = true,
            ),
        )

        assertEquals(null, status.rateLimitPerSecond)
        assertEquals(null, status.userRateLimitPerSecond)
        assertEquals(null, status.slowModeSeconds)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyRoomStatusRowMapper(),
            eq(10L),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            isNull<Int>(),
            isNull<Int>(),
            isNull<Int>(),
            eq(false),
            isNull<Boolean>(),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(true),
            isNull<Int>(),
            eq(true),
            isNull<Int>(),
            eq(true),
            isNull<Int>(),
            eq(false),
            isNull<Boolean>(),
        )
        assertTrue(sqlCaptor.value.contains("room_rate_limit_per_second = CASE WHEN ? THEN NULL"))
        assertTrue(sqlCaptor.value.contains("user_rate_limit_per_second = CASE WHEN ? THEN NULL"))
        assertTrue(sqlCaptor.value.contains("slow_mode_seconds = CASE WHEN ? THEN NULL"))
    }

    @Test
    fun `room policy override는 read replica가 아니라 primary jdbcTemplate로 갱신한다`() {
        val readJdbcTemplate = mock(JdbcTemplate::class.java)
        val writeJdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminMessageRepository(
            jdbcTemplate = readJdbcTemplate,
            writeJdbcTemplate = writeJdbcTemplate,
        )
        val expectedStatus = AdminRoomStatusDto(
            roomId = 10L,
            heatLevel = "VERY_HOT",
            liveFeedMaxMessages = 500,
            liveFeedMaxAgeSeconds = 30,
            rateLimitPerSecond = 1000,
            slowModeSeconds = 5,
            replicaLagMs = 0,
            searchP95LatencyMs = null,
            userRateLimitPerSecond = 2,
            autoPolicyEnabled = false,
        )
        `when`(
            writeJdbcTemplate.queryForObject(
                anyString(),
                anyRoomStatusRowMapper(),
                eq(10L),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(1000),
                eq(2),
                eq(5),
                eq(false),
                isNull<Boolean>(),
                eq("VERY_HOT"),
                eq(500),
                eq(30),
                eq(false),
                eq(1000),
                eq(false),
                eq(2),
                eq(false),
                eq(5),
                eq(false),
                isNull<Boolean>(),
            ),
        ).thenReturn(expectedStatus)

        repository.updateRoomPolicy(
            roomId = 10L,
            request = AdminRoomPolicyUpdateRequest(
                heatLevel = "VERY_HOT",
                liveFeedMaxMessages = 500,
                liveFeedMaxAgeSeconds = 30,
                rateLimitPerSecond = 1000,
                userRateLimitPerSecond = 2,
                slowModeSeconds = 5,
            ),
        )

        verify(writeJdbcTemplate).queryForObject(
            anyString(),
            anyRoomStatusRowMapper(),
            eq(10L),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(1000),
            eq(2),
            eq(5),
            eq(false),
            isNull<Boolean>(),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(false),
            eq(1000),
            eq(false),
            eq(2),
            eq(false),
            eq(5),
            eq(false),
            isNull<Boolean>(),
        )
        verifyNoInteractions(readJdbcTemplate)
    }

    private fun anyAdminMessageRowMapper(): RowMapper<AdminMessageDto> {
        any(RowMapper::class.java)
        return uninitialized()
    }

    private fun anyRoomStatusRowMapper(): RowMapper<AdminRoomStatusDto> {
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
