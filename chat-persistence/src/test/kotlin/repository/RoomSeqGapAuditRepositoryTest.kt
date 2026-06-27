package com.chat.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

class RoomSeqGapAuditRepositoryTest {

    @Test
    fun `auditSince는 chat_messages room_seq gap aggregate를 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomSeqGapAuditRepository(jdbcTemplate)
        val cutoff = LocalDateTime.parse("2026-06-27T10:15:30")
        val expected = RoomSeqGapAuditSummary(
            roomCountWithGaps = 2,
            missingSequenceCount = 5,
            maxGapWidth = 3,
            scannedRoomCount = 7,
        )
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        val rowMapperCaptor = summaryRowMapperCaptor()
        `when`(
            jdbcTemplate.queryForObject(
                captureString(sqlCaptor),
                captureSummaryRowMapper(rowMapperCaptor),
                eq(Timestamp.valueOf(cutoff)),
            ),
        ).thenReturn(expected)

        val summary = repository.auditSince(cutoff)

        assertEquals(expected, summary)
        assertTrue(sqlCaptor.value.contains("FROM chat_messages"))
        assertTrue(sqlCaptor.value.contains("lag(room_seq) OVER (PARTITION BY room_id ORDER BY room_seq)"))
        assertTrue(sqlCaptor.value.contains("created_at >= ?"))
        assertTrue(sqlCaptor.value.contains("room_seq > previous_room_seq + 1"))
        assertTrue(sqlCaptor.value.contains("count(DISTINCT room_id) AS room_count_with_gaps"))
        assertTrue(sqlCaptor.value.contains("count(DISTINCT room_id) AS scanned_room_count"))
    }

    @Test
    fun `auditSince는 aggregate row를 summary로 매핑한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomSeqGapAuditRepository(jdbcTemplate)
        val rowMapperCaptor = summaryRowMapperCaptor()
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                captureSummaryRowMapper(rowMapperCaptor),
                anyTimestamp(),
            ),
        ).thenReturn(RoomSeqGapAuditSummary(0, 0, 0, 0))

        repository.auditSince(LocalDateTime.parse("2026-06-27T10:15:30"))

        val resultSet = mock(ResultSet::class.java)
        `when`(resultSet.getLong("room_count_with_gaps")).thenReturn(3L)
        `when`(resultSet.getLong("missing_sequence_count")).thenReturn(8L)
        `when`(resultSet.getLong("max_gap_width")).thenReturn(5L)
        `when`(resultSet.getLong("scanned_room_count")).thenReturn(11L)

        assertEquals(
            RoomSeqGapAuditSummary(
                roomCountWithGaps = 3,
                missingSequenceCount = 8,
                maxGapWidth = 5,
                scannedRoomCount = 11,
            ),
            rowMapperCaptor.value.mapRow(resultSet, 0),
        )
    }

    @Test
    fun `auditSince는 aggregate row가 없으면 zero summary를 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomSeqGapAuditRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), anySummaryRowMapper(), anyTimestamp()))
            .thenThrow(EmptyResultDataAccessException(1))

        val summary = repository.auditSince(LocalDateTime.parse("2026-06-27T10:15:30"))

        assertEquals(RoomSeqGapAuditSummary(0, 0, 0, 0), summary)
        verify(jdbcTemplate).queryForObject(anyString(), anySummaryRowMapper(), anyTimestamp())
    }

    @Suppress("UNCHECKED_CAST")
    private fun summaryRowMapperCaptor(): ArgumentCaptor<RowMapper<RoomSeqGapAuditSummary>> {
        return ArgumentCaptor.forClass(RowMapper::class.java) as ArgumentCaptor<RowMapper<RoomSeqGapAuditSummary>>
    }

    private fun anyString(): String {
        org.mockito.ArgumentMatchers.anyString()
        return uninitialized()
    }

    private fun captureString(captor: ArgumentCaptor<String>): String {
        captor.capture()
        return uninitialized()
    }

    private fun anyTimestamp(): Timestamp {
        org.mockito.ArgumentMatchers.any(Timestamp::class.java)
        return uninitialized()
    }

    private fun anySummaryRowMapper(): RowMapper<RoomSeqGapAuditSummary> {
        org.mockito.ArgumentMatchers.any<RowMapper<RoomSeqGapAuditSummary>>()
        return uninitialized()
    }

    private fun captureSummaryRowMapper(
        captor: ArgumentCaptor<RowMapper<RoomSeqGapAuditSummary>>,
    ): RowMapper<RoomSeqGapAuditSummary> {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
