package com.chat.persistence.repository

import com.chat.persistence.service.LatestHistoryReadRoutingPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

class PartitionedMessageBatchReadTest {
    @Test
    fun `batch latest history uses selected database and excludes deleted messages`() {
        val primary = database("primary")
        val replica = database("replica")
        listOf(false, true).forEach { usePrimary ->
            val repository = PartitionedMessageReadRepository(
                primary, replica,
                object : LatestHistoryReadRoutingPolicy {
                    override fun usePrimaryForLatestHistory(): Boolean = usePrimary
                },
            )
            val records = repository.findLatestMessagesByRooms(listOf(10L, 20L, 30L))
            assertEquals(2, records.size)
            assertEquals(mapOf(10L to 2L, 20L to 1L), records.associate { it.roomId to it.roomSeq })
            assertEquals(setOf(if (usePrimary) "primary" else "replica"), records.map { it.content }.toSet())
            assertEquals(emptyList<CanonicalMessageRecord>(), repository.findLatestMessagesByRooms(emptyList()))
        }
    }

    private fun database(content: String): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""))
        jdbc.execute(
            """
            CREATE TABLE app_users (id BIGINT PRIMARY KEY, username VARCHAR, display_name VARCHAR, profile_image_url VARCHAR,
                status VARCHAR, is_active BOOLEAN, last_seen_at TIMESTAMP, created_at TIMESTAMP)
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE chat_messages (message_id VARCHAR, client_message_id VARCHAR, room_id BIGINT, room_seq BIGINT,
                stream_shard INT, write_shard INT, fanout_shard INT, sender_id BIGINT, message_type VARCHAR,
                content VARCHAR, is_deleted BOOLEAN, created_at TIMESTAMP)
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO app_users VALUES (7, 'sender', 'Sender', null, null, true, null, CURRENT_TIMESTAMP)")
        listOf(10L to 1L, 10L to 2L, 10L to 3L, 20L to 1L).forEach { (roomId, seq) ->
            jdbc.update("INSERT INTO chat_messages VALUES (?, null, ?, ?, 0, 0, 0, 7, 'TEXT', ?, ?, CURRENT_TIMESTAMP)", "$roomId-$seq", roomId, seq, content, seq == 3L)
        }
        return jdbc
    }
}
