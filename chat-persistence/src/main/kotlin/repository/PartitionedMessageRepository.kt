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
        }).map { updateCount ->
            toInsertResult(updateCount)
        }
    }

    private fun bindInsert(ps: PreparedStatement, request: MessageWriteRequest) {
        ps.setString(1, request.messageId)
        ps.setString(2, request.clientMessageId)
        ps.setLong(3, request.chatRoomId)
        ps.setLong(4, request.roomSeq)
        ps.setInt(5, request.streamShard)
        ps.setInt(6, request.writeShard)
        ps.setInt(7, request.fanoutShard)
        ps.setLong(8, request.senderId)
        ps.setString(9, request.messageType.name)
        ps.setString(10, request.content)
        ps.setTimestamp(11, Timestamp.valueOf(request.createdAt))
    }

    private fun toInsertResult(updateCount: Int): Boolean {
        return updateCount > 0 || updateCount == Statement.SUCCESS_NO_INFO
    }

    private companion object {
        val INSERT_SQL = """
            INSERT INTO chat_messages (
                message_id,
                client_message_id,
                room_id,
                room_seq,
                stream_shard,
                write_shard,
                fanout_shard,
                sender_id,
                message_type,
                content,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
        """.trimIndent()
    }
}
