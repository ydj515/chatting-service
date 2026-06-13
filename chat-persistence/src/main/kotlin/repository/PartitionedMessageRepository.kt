package com.chat.persistence.repository

import com.chat.persistence.service.MessageWriteRequest
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp

@Repository
class PartitionedMessageRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {

    fun batchInsert(requests: List<MessageWriteRequest>): List<Boolean> {
        if (requests.isEmpty()) {
            return emptyList()
        }

        return jdbcTemplate.batchUpdate(INSERT_SQL, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                bindInsert(ps, requests[i])
            }

            override fun getBatchSize(): Int = requests.size
        }).mapIndexed { index, updateCount ->
            toInsertResult(requests[index], updateCount)
        }
    }

    private fun existsCanonicalMessage(request: MessageWriteRequest): Boolean {
        return jdbcTemplate.queryForObject(
            EXISTS_SQL,
            Boolean::class.java,
            request.messageId,
            request.chatRoomId,
            request.roomSeq,
            request.writeShard,
        ) ?: false
    }

    private fun bindInsert(ps: PreparedStatement, request: MessageWriteRequest) {
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

    private fun toInsertResult(request: MessageWriteRequest, updateCount: Int): Boolean {
        if (updateCount > 0 || updateCount == Statement.SUCCESS_NO_INFO) {
            return true
        }

        if (existsCanonicalMessage(request)) {
            return false
        }

        throw IllegalStateException("Message insert returned no row and no duplicate was found: ${request.messageId}")
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

        val EXISTS_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM chat_messages
                WHERE message_id = ?
                  AND room_id = ?
                  AND room_seq = ?
                  AND write_shard = ?
            )
        """.trimIndent()
    }
}
