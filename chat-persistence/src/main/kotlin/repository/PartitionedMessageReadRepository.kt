package com.chat.persistence.repository

import com.chat.domain.model.MessageType
import com.chat.persistence.service.LatestHistoryReadRoutingPolicy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class PartitionedMessageReadRepository(
    private val jdbcTemplate: JdbcTemplate,
    @Qualifier("messageReadJdbcTemplate")
    private val messageReadJdbcTemplate: JdbcTemplate,
    private val latestHistoryReadRoutingPolicy: LatestHistoryReadRoutingPolicy,
) {

    fun findPageByRoom(roomId: Long, pageable: Pageable): Page<CanonicalMessageRecord> {
        val template = latestHistoryTemplate()
        val messages = template.query(
            "$BASE_SELECT $ROOM_FILTER ORDER BY cm.room_seq DESC, cm.created_at DESC LIMIT ? OFFSET ?",
            rowMapper,
            roomId,
            pageable.pageSize,
            pageable.offset,
        )
        val total = countByRoom(roomId, template)
        return PageImpl(messages, pageable, total)
    }

    fun findLatestMessages(roomId: Long, limit: Int): List<CanonicalMessageRecord> {
        return latestHistoryTemplate().query(
            "$BASE_SELECT $ROOM_FILTER ORDER BY cm.room_seq DESC, cm.created_at DESC LIMIT ?",
            rowMapper,
            roomId,
            limit,
        )
    }

    fun findMessagesBefore(roomId: Long, cursor: Long, limit: Int): List<CanonicalMessageRecord> {
        return messageReadJdbcTemplate.query(
            "$BASE_SELECT $ROOM_FILTER AND cm.room_seq < ? ORDER BY cm.room_seq DESC, cm.created_at DESC LIMIT ?",
            rowMapper,
            roomId,
            cursor,
            limit,
        )
    }

    fun findMessagesAfter(roomId: Long, cursor: Long, limit: Int): List<CanonicalMessageRecord> {
        return messageReadJdbcTemplate.query(
            "$BASE_SELECT $ROOM_FILTER AND cm.room_seq > ? ORDER BY cm.room_seq ASC, cm.created_at ASC LIMIT ?",
            rowMapper,
            roomId,
            cursor,
            limit,
        )
    }

    fun findGapMessages(roomId: Long, afterSeq: Long, limit: Int): List<CanonicalMessageRecord> {
        return findMessagesAfter(roomId, afterSeq, limit)
    }

    fun findLatestMessage(roomId: Long): CanonicalMessageRecord? {
        return latestHistoryTemplate().query(
            "$BASE_SELECT $ROOM_FILTER ORDER BY cm.room_seq DESC, cm.created_at DESC LIMIT 1",
            rowMapper,
            roomId,
        ).firstOrNull()
    }

    private fun countByRoom(roomId: Long, template: JdbcTemplate): Long {
        return template.queryForObject(
            "SELECT count(*) FROM chat_messages cm WHERE cm.room_id = ? AND cm.is_deleted = false",
            Long::class.java,
            roomId,
        ) ?: 0L
    }

    private fun latestHistoryTemplate(): JdbcTemplate {
        return if (latestHistoryReadRoutingPolicy.usePrimaryForLatestHistory()) {
            jdbcTemplate
        } else {
            messageReadJdbcTemplate
        }
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
                u.profile_image_url AS sender_profile_image_url,
                u.status AS sender_status,
                u.is_active AS sender_is_active,
                u.last_seen_at AS sender_last_seen_at,
                u.created_at AS sender_created_at,
                cm.message_type,
                cm.content,
                cm.is_deleted,
                cm.created_at
            FROM chat_messages cm
            JOIN app_users u ON u.id = cm.sender_id
        """.trimIndent()
        const val ROOM_FILTER = "WHERE cm.room_id = ? AND cm.is_deleted = false"

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            CanonicalMessageRecord(
                messageId = rs.getString("message_id"),
                clientMessageId = rs.getString("client_message_id"),
                roomId = rs.getLong("room_id"),
                roomSeq = rs.getLong("room_seq"),
                writeShard = rs.getInt("write_shard"),
                senderId = rs.getLong("sender_id"),
                senderUsername = rs.getString("sender_username"),
                senderDisplayName = rs.getString("sender_display_name"),
                senderProfileImageUrl = rs.getString("sender_profile_image_url"),
                senderStatus = rs.getString("sender_status"),
                senderIsActive = rs.getBoolean("sender_is_active"),
                senderLastSeenAt = rs.localDateTime("sender_last_seen_at"),
                senderCreatedAt = rs.localDateTime("sender_created_at") ?: error("sender_created_at is required"),
                messageType = MessageType.valueOf(rs.getString("message_type")),
                content = rs.getString("content"),
                isDeleted = rs.getBoolean("is_deleted"),
                createdAt = rs.localDateTime("created_at") ?: error("created_at is required"),
            )
        }

        fun ResultSet.localDateTime(column: String) = getTimestamp(column)?.toLocalDateTime()
    }
}
