package com.chat.persistence.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

internal data class SessionRevocationJob(val id: String, val userId: Long, val revokedAt: Instant, val attempts: Int, val claimToken: String)

@Repository
class SessionRevocationJobRepository(@Qualifier("jdbcTemplate") private val jdbcTemplate: JdbcTemplate) {
    fun enqueue(id: String, userId: Long, now: Instant) {
        jdbcTemplate.update("INSERT INTO session_revocation_jobs (id, user_id, revoked_at, available_at) VALUES (?, ?, ?, ?)", id, userId, Timestamp.from(now), Timestamp.from(now))
    }

    fun dueIds(now: Instant, limit: Int): List<String> = jdbcTemplate.query(
        "SELECT id FROM session_revocation_jobs WHERE available_at <= ? AND (claimed_until IS NULL OR claimed_until <= ?) ORDER BY available_at, id LIMIT ?",
        { rs, _ -> rs.getString("id") }, Timestamp.from(now), Timestamp.from(now), limit,
    )

    internal fun claim(id: String, token: String, now: Instant, until: Instant): SessionRevocationJob? {
        val changed = jdbcTemplate.update(
            "UPDATE session_revocation_jobs SET claim_token = ?, claimed_until = ? WHERE id = ? AND available_at <= ? AND (claimed_until IS NULL OR claimed_until <= ?)",
            token, Timestamp.from(until), id, Timestamp.from(now), Timestamp.from(now),
        )
        if (changed == 0) return null
        return jdbcTemplate.query(
            "SELECT id, user_id, revoked_at, attempts, claim_token FROM session_revocation_jobs WHERE id = ? AND claim_token = ?",
            { rs, _ -> SessionRevocationJob(rs.getString("id"), rs.getLong("user_id"), rs.getTimestamp("revoked_at").toInstant(), rs.getInt("attempts"), rs.getString("claim_token")) }, id, token,
        ).singleOrNull()
    }

    internal fun complete(job: SessionRevocationJob) {
        jdbcTemplate.update("DELETE FROM session_revocation_jobs WHERE id = ? AND claim_token = ?", job.id, job.claimToken)
    }

    internal fun reschedule(job: SessionRevocationJob, nextAttempt: Instant) {
        jdbcTemplate.update(
            "UPDATE session_revocation_jobs SET attempts = attempts + 1, available_at = ?, claim_token = NULL, claimed_until = NULL WHERE id = ? AND claim_token = ?",
            Timestamp.from(nextAttempt), job.id, job.claimToken,
        )
    }
}
