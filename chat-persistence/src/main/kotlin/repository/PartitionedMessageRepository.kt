package com.chat.persistence.repository

import com.chat.persistence.service.MessageWriteRequest
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp

@Repository
class PartitionedMessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun batchInsert(requests: List<MessageWriteRequest>): List<Boolean> {
        if (requests.isEmpty()) {
            return emptyList()
        }

        return jdbcTemplate.batchUpdate(INSERT_SQL, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val request = requests[i]
                ps.setString(1, request.messageId)
                ps.setString(2, request.clientMessageId)
                ps.setLong(3, request.chatRoomId)
                ps.setLong(4, request.roomSeq)
                ps.setInt(5, request.writeShard)
                ps.setLong(6, request.senderId)
                ps.setString(7, request.messageType.name)
                ps.setString(8, request.content)
                ps.setTimestamp(9, Timestamp.valueOf(request.createdAt))
            }

            override fun getBatchSize(): Int = requests.size
        }).map { updateCount ->
            updateCount > 0 || updateCount == Statement.SUCCESS_NO_INFO
        }
    }

    private companion object {
        val INSERT_SQL = """
            INSERT INTO chat_messages (
                message_id,
                client_message_id,
                room_id,
                room_seq,
                write_shard,
                sender_id,
                message_type,
                content,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
        """.trimIndent()
    }
}
