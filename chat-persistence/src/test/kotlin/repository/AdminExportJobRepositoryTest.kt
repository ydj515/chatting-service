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
import java.time.LocalDateTime
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
                cursorToken = "cursor-token",
                exportedRows = 42,
                outputUri = "file:///tmp/export-1.csv",
            ),
        )

        val job = repository.claimNextPending("worker-1")

        assertNotNull(job)
        assertEquals("export-1", job?.jobId)
        assertEquals("cursor-token", job?.cursorToken)
        assertEquals(42, job?.exportedRows)
        assertEquals("file:///tmp/export-1.csv", job?.outputUri)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyExportJobRecordRowMapper(),
            eq("worker-1"),
        )
        assertTrue(sqlCaptor.value.contains("FOR UPDATE SKIP LOCKED"))
        assertTrue(sqlCaptor.value.contains("status = 'RUNNING'"))
        assertTrue(sqlCaptor.value.contains("claimed_by = ?"))
        assertTrue(sqlCaptor.value.contains("cursor_token"))
        assertTrue(sqlCaptor.value.contains("exported_rows"))
        assertTrue(sqlCaptor.value.contains("output_uri"))
    }

    @Test
    fun `running export job checkpoint는 cursor token과 row count와 output uri를 저장한다`() {
        val jdbcTemplate = RecordingJdbcTemplate(updatedRows = 1)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)

        repository.updateCheckpoint(
            jobId = "export-1",
            cursorToken = "cursor-token",
            exportedRows = 100,
            outputUri = "file:///tmp/export-1.csv",
        )

        assertEquals(
            listOf("cursor-token", 100, "file:///tmp/export-1.csv", "export-1"),
            jdbcTemplate.arguments,
        )
        assertTrue(jdbcTemplate.sql.contains("cursor_token = ?"))
        assertTrue(jdbcTemplate.sql.contains("exported_rows = ?"))
        assertTrue(jdbcTemplate.sql.contains("output_uri = ?"))
        assertTrue(jdbcTemplate.sql.contains("AND status = 'RUNNING'"))
    }

    @Test
    fun `completed export job은 output uri와 completed status를 기록한다`() {
        val jdbcTemplate = RecordingJdbcTemplate(updatedRows = 1)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)

        repository.markCompleted("export-1", "file:///tmp/export-1.csv")

        assertEquals(listOf("file:///tmp/export-1.csv", "export-1"), jdbcTemplate.arguments)
        assertTrue(jdbcTemplate.sql.contains("status = 'COMPLETED'"))
        assertTrue(jdbcTemplate.sql.contains("WHERE job_id = ?"))
        assertTrue(jdbcTemplate.sql.contains("AND status = 'RUNNING'"))
    }

    @Test
    fun `export job status lookup returns output uri and timestamps`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                anyExportJobStatusRowMapper(),
                eq("export-1"),
            ),
        ).thenReturn(
            AdminExportJobStatusRecord(
                jobId = "export-1",
                actor = "admin-local",
                status = "COMPLETED",
                outputUri = "s3://chat-archives/admin-exports/export-1.csv",
                exportedRows = 2,
                errorMessage = null,
                createdAt = LocalDateTime.parse("2026-06-26T00:00:00"),
                startedAt = LocalDateTime.parse("2026-06-26T00:00:01"),
                completedAt = LocalDateTime.parse("2026-06-26T00:00:02"),
            ),
        )

        val record = repository.findById("export-1")

        assertEquals("COMPLETED", record?.status)
        assertEquals("s3://chat-archives/admin-exports/export-1.csv", record?.outputUri)
        assertEquals(2, record?.exportedRows)
        // timestamp 매핑이 깨져도 통과하지 않도록 createdAt/startedAt/completedAt도 함께 검증한다.
        assertEquals(LocalDateTime.parse("2026-06-26T00:00:00"), record?.createdAt)
        assertEquals(LocalDateTime.parse("2026-06-26T00:00:01"), record?.startedAt)
        assertEquals(LocalDateTime.parse("2026-06-26T00:00:02"), record?.completedAt)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            anyExportJobStatusRowMapper(),
            eq("export-1"),
        )
        assertTrue(sqlCaptor.value.contains("FROM admin_message_export_jobs"))
        assertTrue(sqlCaptor.value.contains("WHERE job_id = ?"))
    }

    @Test
    fun `failed export job은 running 상태에서만 실패로 전이한다`() {
        val jdbcTemplate = RecordingJdbcTemplate(updatedRows = 1)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)

        repository.markFailed("export-1", "boom")

        assertEquals(listOf("boom", "export-1"), jdbcTemplate.arguments)
        assertTrue(jdbcTemplate.sql.contains("status = 'FAILED'"))
        assertTrue(jdbcTemplate.sql.contains("WHERE job_id = ?"))
        assertTrue(jdbcTemplate.sql.contains("AND status = 'RUNNING'"))
        assertTrue(!jdbcTemplate.sql.contains("cursor_token = NULL"))
        assertTrue(!jdbcTemplate.sql.contains("exported_rows = 0"))
    }

    @Test
    fun `export job 상태 전이는 running job이 아니면 실패한다`() {
        val jdbcTemplate = RecordingJdbcTemplate(updatedRows = 0)
        val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            repository.markCompleted("export-1", "file:///tmp/export-1.csv")
        }
    }

    private fun anyExportJobRecordRowMapper(): RowMapper<AdminExportJobRecord> {
        any(RowMapper::class.java)
        return uninitialized()
    }

    private fun anyExportJobStatusRowMapper(): RowMapper<AdminExportJobStatusRecord> {
        any(RowMapper::class.java)
        return uninitialized()
    }

    private class RecordingJdbcTemplate(
        private val updatedRows: Int,
    ) : JdbcTemplate() {
        lateinit var sql: String
        lateinit var arguments: List<Any?>

        override fun update(sql: String, vararg args: Any?): Int {
            this.sql = sql
            this.arguments = args.toList()
            return updatedRows
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
