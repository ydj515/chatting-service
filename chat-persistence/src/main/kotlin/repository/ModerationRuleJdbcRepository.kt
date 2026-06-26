package com.chat.persistence.repository

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

data class ModerationRuleRecord(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val pattern: String,
    val matchType: ModerationMatchType,
    val action: ModerationAction,
    val reason: String?,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun toDto(): AdminModerationRuleDto = AdminModerationRuleDto(
        id = id,
        scopeType = scopeType,
        roomId = roomId,
        pattern = pattern,
        matchType = matchType,
        action = action,
        reason = reason,
        enabled = enabled,
        createdBy = createdBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Repository
class ModerationRuleJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    @Cacheable(value = ["moderationRules"], key = "#roomId")
    fun activeRulesForRoom(roomId: Long): List<ModerationRuleRecord> {
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            FROM moderation_rules
            WHERE enabled = true
              AND (
                scope_type = 'GLOBAL'
                OR (scope_type = 'ROOM' AND room_id = ?)
              )
            ORDER BY scope_type ASC, id ASC
            """.trimIndent(),
            ROW_MAPPER,
            roomId,
        )
    }

    fun listRules(roomId: Long?, enabled: Boolean?): List<ModerationRuleRecord> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (roomId != null) {
            conditions += "(scope_type = 'GLOBAL' OR room_id = ?)"
            args += roomId
        }
        if (enabled != null) {
            conditions += "enabled = ?"
            args += enabled
        }

        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            FROM moderation_rules
            $where
            ORDER BY id DESC
            """.trimIndent(),
            ROW_MAPPER,
            *args.toTypedArray(),
        )
    }

    fun create(actor: String, request: AdminCreateModerationRuleRequest): ModerationRuleRecord {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO moderation_rules (scope_type, room_id, pattern, match_type, action, reason, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            """.trimIndent(),
            ROW_MAPPER,
            request.scopeType.name,
            request.roomId,
            request.pattern,
            request.matchType.name,
            request.action.name,
            request.reason,
            actor,
        ) ?: error("failed to create moderation rule")
    }

    fun update(ruleId: Long, request: AdminUpdateModerationRuleRequest): ModerationRuleRecord {
        // queryForObject는 0행일 때 EmptyResultDataAccessException을 던지므로(null 반환 아님)
        // 존재하지 않는 ruleId를 명시적으로 not-found 오류로 변환한다.
        return try {
            jdbcTemplate.queryForObject(
                """
                UPDATE moderation_rules
                SET pattern = COALESCE(?, pattern),
                    reason = COALESCE(?, reason),
                    enabled = COALESCE(?, enabled),
                    updated_at = now()
                WHERE id = ?
                RETURNING id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
                """.trimIndent(),
                ROW_MAPPER,
                request.pattern,
                request.reason,
                request.enabled,
                ruleId,
            )
        } catch (e: EmptyResultDataAccessException) {
            null
        } ?: error("moderation rule not found: $ruleId")
    }

    fun disable(ruleId: Long): ModerationRuleRecord {
        return update(ruleId, AdminUpdateModerationRuleRequest(enabled = false))
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            ModerationRuleRecord(
                id = rs.getLong("id"),
                scopeType = ModerationScopeType.valueOf(rs.getString("scope_type")),
                roomId = rs.getObject("room_id", java.lang.Long::class.java)?.toLong(),
                pattern = rs.getString("pattern"),
                matchType = ModerationMatchType.valueOf(rs.getString("match_type")),
                action = ModerationAction.valueOf(rs.getString("action")),
                reason = rs.getString("reason"),
                enabled = rs.getBoolean("enabled"),
                createdBy = rs.getString("created_by"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
