package com.chat.persistence.repository

import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageCursor
import com.chat.domain.dto.AdminMessageSearchCursor
import com.chat.domain.dto.AdminMessageSearchMode
import com.chat.domain.dto.AdminRoomPolicyUpdateRequest
import com.chat.domain.dto.AdminRoomStatusDto
import com.chat.domain.model.MessageType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class AdminMessageRepository(
    @Qualifier("messageReadJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    @Qualifier("jdbcTemplate")
    private val writeJdbcTemplate: JdbcTemplate,
) {

    fun findRoomMessages(
        roomId: Long,
        from: Instant?,
        to: Instant?,
        cursor: AdminMessageCursor?,
        limit: Int,
    ): List<AdminMessageDto> {
        val where = mutableListOf("cm.room_id = ?")
        val args = mutableListOf<Any>(roomId)
        if (from != null) {
            where += "cm.created_at >= ?"
            args += Timestamp.from(from)
        }
        if (to != null) {
            where += "cm.created_at < ?"
            args += Timestamp.from(to)
        }
        if (cursor != null) {
            where += """
                (
                    cm.room_seq < ?
                    OR (cm.room_seq = ? AND cm.created_at < ?)
                    OR (cm.room_seq = ? AND cm.created_at = ? AND cm.message_id < ?)
                )
            """.trimIndent()
            args += cursor.roomSeq
            args += cursor.roomSeq
            args += Timestamp.from(cursor.createdAt)
            args += cursor.roomSeq
            args += Timestamp.from(cursor.createdAt)
            args += cursor.messageId
        }
        args += limit

        return jdbcTemplate.query(
            """
            $BASE_SELECT
            WHERE ${where.joinToString(" AND ")}
            ORDER BY cm.room_seq DESC, cm.created_at DESC, cm.message_id DESC
            LIMIT ?
            """.trimIndent(),
            messageRowMapper,
            *args.toTypedArray(),
        )
    }

    fun searchMessages(
        query: String,
        searchMode: AdminMessageSearchMode,
        roomId: Long?,
        from: Instant?,
        to: Instant?,
        senderId: Long?,
        cursor: AdminMessageSearchCursor?,
        limit: Int,
    ): List<AdminMessageDto> {
        val normalizedQuery = query.trim()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (normalizedQuery.isNotEmpty()) {
            when (searchMode) {
                AdminMessageSearchMode.FTS -> {
                    where += "cm.content_tsv @@ plainto_tsquery('simple', ?)"
                    args += normalizedQuery
                }
                AdminMessageSearchMode.CONTAINS -> {
                    where += "cm.content ILIKE ?"
                    args += "%$normalizedQuery%"
                }
            }
        }
        if (roomId != null) {
            where += "cm.room_id = ?"
            args += roomId
        }
        if (from != null) {
            where += "cm.created_at >= ?"
            args += Timestamp.from(from)
        }
        if (to != null) {
            where += "cm.created_at < ?"
            args += Timestamp.from(to)
        }
        if (senderId != null) {
            where += "cm.sender_id = ?"
            args += senderId
        }
        if (cursor != null) {
            where += """
                (
                    cm.created_at < ?
                    OR (cm.created_at = ? AND cm.room_seq < ?)
                    OR (cm.created_at = ? AND cm.room_seq = ? AND cm.message_id < ?)
                )
            """.trimIndent()
            args += Timestamp.from(cursor.createdAt)
            args += Timestamp.from(cursor.createdAt)
            args += cursor.roomSeq
            args += Timestamp.from(cursor.createdAt)
            args += cursor.roomSeq
            args += cursor.messageId
        }
        if (where.isEmpty()) {
            return emptyList()
        }
        args += limit

        return jdbcTemplate.query(
            """
            $BASE_SELECT
            WHERE ${where.joinToString(" AND ")}
            ORDER BY cm.created_at DESC, cm.room_seq DESC, cm.message_id DESC
            LIMIT ?
            """.trimIndent(),
            messageRowMapper,
            *args.toTypedArray(),
        )
    }

    fun findRoomStatus(roomId: Long): AdminRoomStatusDto {
        return try {
            jdbcTemplate.queryForObject(
                """
                SELECT
                    room_id,
                    hot_room_policy AS heat_level,
                    live_feed_max_messages,
                    live_feed_max_age_seconds,
                    room_rate_limit_per_second AS rate_limit_per_second,
                    user_rate_limit_per_second,
                    slow_mode_seconds,
                    auto_policy_enabled,
                    moderator_priority,
                    0::bigint AS replica_lag_ms,
                    NULL::bigint AS search_p95_latency_ms
                FROM room_storage_configs
                WHERE room_id = ?
                """.trimIndent(),
                roomStatusRowMapper,
                roomId,
            ) ?: defaultRoomStatus(roomId)
        } catch (e: EmptyResultDataAccessException) {
            defaultRoomStatus(roomId)
        }
    }

    fun updateRoomPolicy(
        roomId: Long,
        request: AdminRoomPolicyUpdateRequest,
    ): AdminRoomStatusDto {
        val clearRateLimit = request.clearRateLimit == true
        val clearUserRateLimit = request.clearUserRateLimit == true
        val clearSlowMode = request.clearSlowMode == true
        val insertedRateLimit = request.rateLimitPerSecond.takeUnless { clearRateLimit }
        val insertedUserRateLimit = request.userRateLimitPerSecond.takeUnless { clearUserRateLimit }
        val insertedSlowMode = request.slowModeSeconds.takeUnless { clearSlowMode }

        return writeJdbcTemplate.queryForObject(
            """
            INSERT INTO room_storage_configs (
                room_id,
                hot_room_policy,
                live_feed_max_messages,
                live_feed_max_age_seconds,
                room_rate_limit_per_second,
                user_rate_limit_per_second,
                slow_mode_seconds,
                auto_policy_enabled,
                moderator_priority,
                updated_at
            )
            VALUES (
                ?,
                COALESCE(?, 'NORMAL'),
                COALESCE(?, 1000),
                COALESCE(?, 60),
                ?,
                ?,
                ?,
                ?,
                COALESCE(?, true),
                now()
            )
            ON CONFLICT (room_id) DO UPDATE SET
                hot_room_policy = COALESCE(?, room_storage_configs.hot_room_policy),
                live_feed_max_messages = COALESCE(?, room_storage_configs.live_feed_max_messages),
                live_feed_max_age_seconds = COALESCE(?, room_storage_configs.live_feed_max_age_seconds),
                room_rate_limit_per_second = CASE WHEN ? THEN NULL ELSE COALESCE(?, room_storage_configs.room_rate_limit_per_second) END,
                user_rate_limit_per_second = CASE WHEN ? THEN NULL ELSE COALESCE(?, room_storage_configs.user_rate_limit_per_second) END,
                slow_mode_seconds = CASE WHEN ? THEN NULL ELSE COALESCE(?, room_storage_configs.slow_mode_seconds) END,
                auto_policy_enabled = ?,
                moderator_priority = COALESCE(?, room_storage_configs.moderator_priority),
                updated_at = now()
            RETURNING
                room_id,
                hot_room_policy AS heat_level,
                live_feed_max_messages,
                live_feed_max_age_seconds,
                room_rate_limit_per_second AS rate_limit_per_second,
                user_rate_limit_per_second,
                slow_mode_seconds,
                auto_policy_enabled,
                moderator_priority,
                0::bigint AS replica_lag_ms,
                NULL::bigint AS search_p95_latency_ms
            """.trimIndent(),
            roomStatusRowMapper,
            roomId,
            request.heatLevel,
            request.liveFeedMaxMessages,
            request.liveFeedMaxAgeSeconds,
            insertedRateLimit,
            insertedUserRateLimit,
            insertedSlowMode,
            request.autoPolicyEnabled ?: false,
            request.moderatorPriority,
            request.heatLevel,
            request.liveFeedMaxMessages,
            request.liveFeedMaxAgeSeconds,
            clearRateLimit,
            request.rateLimitPerSecond,
            clearUserRateLimit,
            request.userRateLimitPerSecond,
            clearSlowMode,
            request.slowModeSeconds,
            request.autoPolicyEnabled ?: false,
            request.moderatorPriority,
        ) ?: defaultRoomStatus(roomId)
    }

    private fun defaultRoomStatus(roomId: Long): AdminRoomStatusDto {
        return AdminRoomStatusDto(
            roomId = roomId,
            heatLevel = "NORMAL",
            liveFeedMaxMessages = 1000,
            liveFeedMaxAgeSeconds = 60,
            rateLimitPerSecond = null,
            slowModeSeconds = null,
            replicaLagMs = 0,
            searchP95LatencyMs = null,
            userRateLimitPerSecond = null,
            autoPolicyEnabled = true,
            moderatorPriority = true,
        )
    }

    private companion object {
        val BASE_SELECT = """
            SELECT
                cm.message_id,
                cm.client_message_id,
                cm.room_id,
                cm.room_seq,
                cm.write_shard,
                cm.sender_id,
                u.username AS sender_username,
                u.display_name AS sender_display_name,
                cm.message_type,
                cm.content,
                cm.is_deleted,
                cm.created_at
            FROM chat_messages cm
            JOIN app_users u ON u.id = cm.sender_id
        """.trimIndent()

        val messageRowMapper = RowMapper { rs: ResultSet, _: Int ->
            AdminMessageDto(
                messageId = rs.getString("message_id"),
                clientMessageId = rs.getString("client_message_id"),
                roomId = rs.getLong("room_id"),
                roomSeq = rs.getLong("room_seq"),
                writeShard = rs.getInt("write_shard"),
                senderId = rs.getLong("sender_id"),
                senderUsername = rs.getString("sender_username"),
                senderDisplayName = rs.getString("sender_display_name"),
                messageType = MessageType.valueOf(rs.getString("message_type")),
                content = rs.getString("content"),
                isDeleted = rs.getBoolean("is_deleted"),
                createdAt = rs.instant("created_at") ?: error("created_at is required"),
            )
        }

        val roomStatusRowMapper = RowMapper { rs: ResultSet, _: Int ->
            AdminRoomStatusDto(
                roomId = rs.getLong("room_id"),
                heatLevel = rs.getString("heat_level") ?: "NORMAL",
                liveFeedMaxMessages = rs.getInt("live_feed_max_messages"),
                liveFeedMaxAgeSeconds = rs.getInt("live_feed_max_age_seconds"),
                rateLimitPerSecond = rs.nullableInt("rate_limit_per_second"),
                slowModeSeconds = rs.nullableInt("slow_mode_seconds"),
                replicaLagMs = rs.nullableLong("replica_lag_ms"),
                searchP95LatencyMs = rs.nullableLong("search_p95_latency_ms"),
                userRateLimitPerSecond = rs.nullableInt("user_rate_limit_per_second"),
                autoPolicyEnabled = rs.getBoolean("auto_policy_enabled"),
                moderatorPriority = rs.getBoolean("moderator_priority"),
            )
        }

        fun ResultSet.instant(column: String): Instant? {
            return getTimestamp(column)?.toInstant()
        }

        fun ResultSet.nullableInt(column: String): Int? {
            val value = getInt(column)
            return if (wasNull()) null else value
        }

        fun ResultSet.nullableLong(column: String): Long? {
            val value = getLong(column)
            return if (wasNull()) null else value
        }
    }
}
