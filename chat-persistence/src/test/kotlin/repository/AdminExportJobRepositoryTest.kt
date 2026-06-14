package com.chat.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminExportJobRepositoryTest {

    @Test
    fun `export job insert는 actor와 request json을 바인딩하고 pending job을 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminExportJobRepository(
            jdbcTemplate = jdbcTemplate,
            clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC),
            jobIdGenerator = { "export-1" },
        )

        val job = repository.create(
            actor = "admin-local",
            requestJson = """{"roomId":10}""",
        )

        assertEquals("export-1", job.jobId)
        assertEquals("PENDING", job.status)
        assertEquals("2026-06-14T00:00", job.createdAt.toString())
        verify(jdbcTemplate).update(
            contains("INSERT INTO admin_message_export_jobs"),
            eq("export-1"),
            eq("admin-local"),
            eq("PENDING"),
            eq("""{"roomId":10}"""),
            eq(java.sql.Timestamp.from(Instant.parse("2026-06-14T00:00:00Z"))),
        )
    }

    @Test
    fun `pending export job claim은 skip locked로 하나의 worker에 할당한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                anyExportJobRecordRowMapper(),
                eq("worker-1"),
            ),
        ).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10}""",
            ),
        )

        val job = repository.claimNextPending("worker-1")

        assertNotNull(job)
        assertEquals("export-1", job?.jobId)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyExportJobRecordRowMapper(),
            eq("worker-1"),
        )
        assertTrue(sqlCaptor.value.contains("FOR UPDATE SKIP LOCKED"))
        assertTrue(sqlCaptor.value.contains("status = 'RUNNING'"))
        assertTrue(sqlCaptor.value.contains("claimed_by = ?"))
    }

    @Test
    fun `completed export job은 output uri와 completed status를 기록한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)

        repository.markCompleted("export-1", "file:///tmp/export-1.csv")

        verify(jdbcTemplate).update(
            contains("status = 'COMPLETED'"),
            eq("file:///tmp/export-1.csv"),
            eq("export-1"),
        )
    }

    private fun anyExportJobRecordRowMapper(): RowMapper<AdminExportJobRecord> {
        any(RowMapper::class.java)
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
