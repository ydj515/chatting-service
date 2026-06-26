package com.chat.persistence.repository

import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class UserSanctionRecord(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val userId: Long,
    val type: UserSanctionType,
    val reason: String?,
    val expiresAt: Instant?,
    val active: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val revokedBy: String?,
    val revokedAt: Instant?,
) {
    fun toDto(): AdminUserSanctionDto = AdminUserSanctionDto(
        id = id,
        scopeType = scopeType,
        roomId = roomId,
        userId = userId,
        type = type,
        reason = reason,
        expiresAt = expiresAt,
        active = active,
        createdBy = createdBy,
        createdAt = createdAt,
        revokedBy = revokedBy,
        revokedAt = revokedAt,
    )
}

@Repository
class UserSanctionJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    @Cacheable(
        value = ["userSanctions"],
        key = "T(java.lang.String).valueOf(#roomId).concat(':').concat(T(java.lang.String).valueOf(#userId))",
    )
    fun activeSanctionsForUser(roomId: Long, userId: Long, now: Instant): List<UserSanctionRecord> {
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            FROM user_sanctions
            WHERE active = true
              AND user_id = ?
              AND scope_type = 'ROOM'
              AND room_id = ?
              AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY id DESC
            """.trimIndent(),
            ROW_MAPPER,
            userId,
            roomId,
            Timestamp.from(now),
        )
    }

    fun listSanctions(roomId: Long?, userId: Long?, active: Boolean?): List<UserSanctionRecord> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (roomId != null) {
            conditions += "room_id = ?"
            args += roomId
        }
        if (userId != null) {
            conditions += "user_id = ?"
            args += userId
        }
        if (active != null) {
            conditions += "active = ?"
            args += active
        }

        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            FROM user_sanctions
            $where
            ORDER BY id DESC
            """.trimIndent(),
            ROW_MAPPER,
            *args.toTypedArray(),
        )
    }

    fun create(actor: String, request: AdminCreateUserSanctionRequest): UserSanctionRecord {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO user_sanctions (scope_type, room_id, user_id, type, reason, expires_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            """.trimIndent(),
            ROW_MAPPER,
            request.scopeType.name,
            request.roomId,
            request.userId,
            request.type.name,
            request.reason,
            request.expiresAt?.let { Timestamp.from(it) },
            actor,
        ) ?: error("failed to create user sanction")
    }

    fun revoke(actor: String, sanctionId: Long): UserSanctionRecord {
        return jdbcTemplate.queryForObject(
            """
            UPDATE user_sanctions
            SET active = false,
                revoked_by = ?,
                revoked_at = now()
            WHERE id = ?
            RETURNING id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            """.trimIndent(),
            ROW_MAPPER,
            actor,
            sanctionId,
        ) ?: error("user sanction not found: $sanctionId")
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            UserSanctionRecord(
                id = rs.getLong("id"),
                scopeType = ModerationScopeType.valueOf(rs.getString("scope_type")),
                roomId = rs.getObject("room_id", java.lang.Long::class.java)?.toLong(),
                userId = rs.getLong("user_id"),
                type = UserSanctionType.valueOf(rs.getString("type")),
                reason = rs.getString("reason"),
                expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                active = rs.getBoolean("active"),
                createdBy = rs.getString("created_by"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                revokedBy = rs.getString("revoked_by"),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }
    }
}
