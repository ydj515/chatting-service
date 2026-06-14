package com.chat.persistence.repository

import com.chat.domain.dto.AdminExportJobDto
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

data class AdminExportJobRecord(
    val jobId: String,
    val actor: String,
    val requestJson: String,
)

@Repository
class AdminExportJobRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
    private val jobIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun create(actor: String, requestJson: String): AdminExportJobDto {
        val jobId = jobIdGenerator()
        val createdAtInstant = clock.instant()
        val createdAt = LocalDateTime.ofInstant(createdAtInstant, ZoneOffset.UTC)
        val status = "PENDING"

        jdbcTemplate.update(
            """
            INSERT INTO admin_message_export_jobs (
                job_id,
                actor,
                status,
                request,
                created_at
            )
            VALUES (?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            jobId,
            actor,
            status,
            requestJson,
            Timestamp.from(createdAtInstant),
        )

        return AdminExportJobDto(
            jobId = jobId,
            status = status,
            createdAt = createdAt,
        )
    }

    fun claimNextPending(workerId: String): AdminExportJobRecord? {
        return try {
            jdbcTemplate.queryForObject(
                """
                UPDATE admin_message_export_jobs
                SET
                    status = 'RUNNING',
                    claimed_by = ?,
                    started_at = now(),
                    error_message = NULL
                WHERE job_id = (
                    SELECT job_id
                    FROM admin_message_export_jobs
                    WHERE status = 'PENDING'
                    ORDER BY created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING job_id, actor, request::text AS request_json
                """.trimIndent(),
                exportJobRowMapper,
                workerId,
            )
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun markCompleted(jobId: String, outputUri: String) {
        val updatedRows = jdbcTemplate.update(
            """
            UPDATE admin_message_export_jobs
            SET
                status = 'COMPLETED',
                output_uri = ?,
                error_message = NULL,
                completed_at = now()
            WHERE job_id = ?
              AND status = 'RUNNING'
            """.trimIndent(),
            outputUri,
            jobId,
        )
        requireRunningTransition(updatedRows, jobId)
    }

    fun markFailed(jobId: String, errorMessage: String) {
        val updatedRows = jdbcTemplate.update(
            """
            UPDATE admin_message_export_jobs
            SET
                status = 'FAILED',
                error_message = ?,
                completed_at = now()
            WHERE job_id = ?
              AND status = 'RUNNING'
            """.trimIndent(),
            errorMessage.take(MAX_ERROR_MESSAGE_LENGTH),
            jobId,
        )
        requireRunningTransition(updatedRows, jobId)
    }

    private fun requireRunningTransition(updatedRows: Int, jobId: String) {
        if (updatedRows != 1) {
            throw IllegalStateException("Admin export job $jobId is not RUNNING or does not exist")
        }
    }

    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 2000

        val exportJobRowMapper = RowMapper { rs: ResultSet, _: Int ->
            AdminExportJobRecord(
                jobId = rs.getString("job_id"),
                actor = rs.getString("actor"),
                requestJson = rs.getString("request_json"),
            )
        }
    }
}
