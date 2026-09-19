package com.chat.persistence.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

internal data class SanctionCacheJob(val id: String, val cacheKey: String, val attempts: Int, val claimToken: String)

@Repository
class SanctionCacheInvalidationRepository(@Qualifier("jdbcTemplate") private val jdbcTemplate: JdbcTemplate) {
    fun enqueue(id: String, cacheKey: String, now: Instant) {
        jdbcTemplate.update("INSERT INTO sanction_cache_invalidations (id, cache_key, available_at) VALUES (?, ?, ?)", id, cacheKey, Timestamp.from(now))
    }

    fun dueIds(now: Instant, limit: Int): List<String> = jdbcTemplate.query(
        "SELECT id FROM sanction_cache_invalidations WHERE available_at <= ? AND (claimed_until IS NULL OR claimed_until <= ?) ORDER BY available_at, id LIMIT ?",
        { rs, _ -> rs.getString("id") }, Timestamp.from(now), Timestamp.from(now), limit,
    )

    internal fun claim(id: String, token: String, now: Instant, until: Instant): SanctionCacheJob? {
        val changed = jdbcTemplate.update(
            "UPDATE sanction_cache_invalidations SET claim_token = ?, claimed_until = ? WHERE id = ? AND available_at <= ? AND (claimed_until IS NULL OR claimed_until <= ?)",
            token, Timestamp.from(until), id, Timestamp.from(now), Timestamp.from(now),
        )
        if (changed == 0) return null
        return jdbcTemplate.query(
            "SELECT id, cache_key, attempts, claim_token FROM sanction_cache_invalidations WHERE id = ? AND claim_token = ?",
            { rs, _ -> SanctionCacheJob(rs.getString("id"), rs.getString("cache_key"), rs.getInt("attempts"), rs.getString("claim_token")) }, id, token,
        ).singleOrNull()
    }

    internal fun complete(job: SanctionCacheJob) {
        jdbcTemplate.update("DELETE FROM sanction_cache_invalidations WHERE id = ? AND claim_token = ?", job.id, job.claimToken)
    }

    internal fun reschedule(job: SanctionCacheJob, nextAttempt: Instant) {
        jdbcTemplate.update(
            "UPDATE sanction_cache_invalidations SET attempts = attempts + 1, available_at = ?, claim_token = NULL, claimed_until = NULL WHERE id = ? AND claim_token = ?",
            Timestamp.from(nextAttempt), job.id, job.claimToken,
        )
    }
}
