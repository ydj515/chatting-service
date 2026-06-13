package com.chat.persistence.repository

import com.chat.persistence.service.RoomStorageConfigReader
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class RoomStorageConfigJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) : RoomStorageConfigReader {

    override fun currentShardCount(roomId: Long): Int {
        return try {
            jdbcTemplate.queryForObject(
                SELECT_CURRENT_SHARD_COUNT_SQL,
                Int::class.java,
                roomId,
            )?.coerceAtLeast(MIN_SHARD_COUNT) ?: DEFAULT_SHARD_COUNT
        } catch (e: EmptyResultDataAccessException) {
            DEFAULT_SHARD_COUNT
        }
    }

    private companion object {
        const val DEFAULT_SHARD_COUNT = 1
        const val MIN_SHARD_COUNT = 1
        const val SELECT_CURRENT_SHARD_COUNT_SQL = """
            SELECT current_shard_count
            FROM room_storage_configs
            WHERE room_id = ?
        """
    }
}
