package com.chat.persistence.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

data class RoomSeqGapAuditSummary(
    val roomCountWithGaps: Long,
    val missingSequenceCount: Long,
    val maxGapWidth: Long,
    val scannedRoomCount: Long,
) {
    companion object {
        val ZERO = RoomSeqGapAuditSummary(
            roomCountWithGaps = 0,
            missingSequenceCount = 0,
            maxGapWidth = 0,
            scannedRoomCount = 0,
        )
    }
}

@Repository
class RoomSeqGapAuditRepository(
    @Qualifier("messageReadJdbcTemplate")
    private val messageReadJdbcTemplate: JdbcTemplate,
) {

    fun auditSince(cutoff: Instant): RoomSeqGapAuditSummary {
        val cutoffTimestamp = Timestamp.from(cutoff)
        return try {
            messageReadJdbcTemplate.queryForObject(
                AUDIT_SQL,
                ROW_MAPPER,
                cutoffTimestamp,
                cutoffTimestamp,
                cutoffTimestamp,
                cutoffTimestamp,
            ) ?: RoomSeqGapAuditSummary.ZERO
        } catch (e: EmptyResultDataAccessException) {
            RoomSeqGapAuditSummary.ZERO
        }
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs, _ ->
            RoomSeqGapAuditSummary(
                roomCountWithGaps = rs.getLong("room_count_with_gaps").coerceAtLeast(0),
                missingSequenceCount = rs.getLong("missing_sequence_count").coerceAtLeast(0),
                maxGapWidth = rs.getLong("max_gap_width").coerceAtLeast(0),
                scannedRoomCount = rs.getLong("scanned_room_count").coerceAtLeast(0),
            )
        }

        val AUDIT_SQL = """
            WITH recent_rooms AS (
                SELECT DISTINCT room_id
                FROM chat_messages
                WHERE created_at >= ?
            ),
            candidate_rows AS (
                SELECT
                    cm.room_id,
                    cm.room_seq,
                    cm.created_at
                FROM chat_messages cm
                JOIN recent_rooms rr ON rr.room_id = cm.room_id
                WHERE cm.created_at >= ?

                UNION ALL

                SELECT
                    predecessor.room_id,
                    predecessor.room_seq,
                    predecessor.created_at
                FROM recent_rooms rr
                JOIN LATERAL (
                    SELECT
                        cm.room_id,
                        cm.room_seq,
                        cm.created_at
                    FROM chat_messages cm
                    WHERE cm.room_id = rr.room_id
                      AND cm.created_at < ?
                    ORDER BY cm.room_seq DESC
                    LIMIT 1
                ) predecessor ON true
            ),
            ordered AS (
                SELECT
                    room_id,
                    created_at,
                    room_seq,
                    lag(room_seq) OVER (PARTITION BY room_id ORDER BY room_seq) AS previous_room_seq
                FROM candidate_rows
            ),
            gaps AS (
                SELECT
                    room_id,
                    room_seq - previous_room_seq - 1 AS gap_width
                FROM ordered
                WHERE ordered.created_at >= ?
                  AND previous_room_seq IS NOT NULL
                  AND room_seq > previous_room_seq + 1
            ),
            gap_summary AS (
                SELECT
                    count(DISTINCT room_id) AS room_count_with_gaps,
                    coalesce(sum(gap_width), 0) AS missing_sequence_count,
                    coalesce(max(gap_width), 0) AS max_gap_width
                FROM gaps
            ),
            scanned AS (
                SELECT count(*) AS scanned_room_count
                FROM recent_rooms
            )
            SELECT
                gap_summary.room_count_with_gaps,
                gap_summary.missing_sequence_count,
                gap_summary.max_gap_width,
                scanned.scanned_room_count
            FROM gap_summary
            CROSS JOIN scanned
        """.trimIndent()
    }
}
