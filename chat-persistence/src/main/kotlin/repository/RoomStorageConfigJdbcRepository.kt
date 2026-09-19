package com.chat.persistence.repository

import com.chat.persistence.service.RoomAdmissionPolicy
import com.chat.persistence.service.RoomAdmissionPolicyReader
import com.chat.persistence.service.RoomShardConfig
import com.chat.persistence.service.RoomStorageConfigReader
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.SingleColumnRowMapper
import org.springframework.stereotype.Repository

@Repository
class RoomStorageConfigJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) : RoomStorageConfigReader, RoomAdmissionPolicyReader {
    override fun currentShardCount(roomId: Long): Int =
        DataAccessUtils.singleResult(
            jdbcTemplate.query(
                SELECT_CURRENT_SHARD_COUNT_SQL,
                SingleColumnRowMapper(Int::class.java),
                roomId,
            ),
        )?.coerceAtLeast(MIN_SHARD_COUNT) ?: DEFAULT_SHARD_COUNT

    @Cacheable(value = ["roomShardConfigs"], key = "#roomId")
    override fun shardConfig(roomId: Long): RoomShardConfig {
        val config = DataAccessUtils.singleResult(
            jdbcTemplate.query(
                SELECT_SHARD_CONFIG_SQL,
                { rs, _ ->
                    RoomShardConfig(
                        writeShardCount = rs.getInt("current_shard_count"),
                        fanoutShardCount = rs.getInt("fanout_shard_count"),
                    )
                },
                roomId,
            ),
        ) ?: RoomShardConfig()
        return config.sanitized()
    }

    @Cacheable(value = ["roomAdmissionPolicies"], key = "#roomId")
    override fun admissionPolicy(roomId: Long): RoomAdmissionPolicy =
        DataAccessUtils.singleResult(
            jdbcTemplate.query(
                SELECT_ADMISSION_POLICY_SQL,
                { rs, _ ->
                    RoomAdmissionPolicy(
                        roomRateLimitPerSecond = rs.nullableInt("room_rate_limit_per_second"),
                        userRateLimitPerSecond = rs.nullableInt("user_rate_limit_per_second"),
                        slowModeSeconds = rs.nullableInt("slow_mode_seconds"),
                        moderatorPriority = rs.getBoolean("moderator_priority"),
                    )
                },
                roomId,
            ),
        ) ?: RoomAdmissionPolicy()

    private fun RoomShardConfig.sanitized(): RoomShardConfig =
        copy(
            writeShardCount = writeShardCount.coerceAtLeast(MIN_SHARD_COUNT),
            fanoutShardCount = fanoutShardCount.coerceAtLeast(MIN_SHARD_COUNT),
        )

    private fun java.sql.ResultSet.nullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private companion object {
        const val DEFAULT_SHARD_COUNT = 1
        const val MIN_SHARD_COUNT = 1
        const val SELECT_CURRENT_SHARD_COUNT_SQL = """
            SELECT current_shard_count
            FROM room_storage_configs
            WHERE room_id = ?
        """
        const val SELECT_SHARD_CONFIG_SQL = """
            SELECT
                current_shard_count,
                fanout_shard_count
            FROM room_storage_configs
            WHERE room_id = ?
        """
        const val SELECT_ADMISSION_POLICY_SQL = """
            SELECT
                room_rate_limit_per_second,
                user_rate_limit_per_second,
                slow_mode_seconds,
                moderator_priority
            FROM room_storage_configs
            WHERE room_id = ?
        """
    }
}
