package com.chat.persistence.service

import com.chat.persistence.config.ChatReadDataSourceProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class ReadReplicaLagPolicy(
    @Qualifier("messageReadJdbcTemplate")
    private val messageReadJdbcTemplate: JdbcTemplate,
    private val properties: ChatReadDataSourceProperties,
    @Qualifier("jdbcTemplate") private val primaryJdbcTemplate: JdbcTemplate,
) : LatestHistoryReadRoutingPolicy {
    private val logger = LoggerFactory.getLogger(ReadReplicaLagPolicy::class.java)

    // metric publisher용 현재 replica lag(ms). replica 비활성 시 0을 반환한다.
    fun currentLagMillis(): Long {
        if (!properties.enabled) {
            return 0L
        }
        val primaryLsn = checkNotNull(primaryJdbcTemplate.queryForObject("SELECT pg_current_wal_lsn()::text", String::class.java)) {
            "Primary WAL position is unavailable"
        }
        return checkNotNull(messageReadJdbcTemplate.queryForObject(REPLICA_LAG_MILLIS_SQL, Long::class.javaObjectType, primaryLsn)) {
            "Replica replay progress is unavailable"
        }
    }

    override fun usePrimaryForLatestHistory(): Boolean {
        if (!properties.enabled) {
            return false
        }

        return try {
            val lagMillis = currentLagMillis()
            lagMillis > properties.latestHistoryMaxReplicaLag.toMillis()
        } catch (e: Exception) {
            logger.warn("Failed to measure read replica lag; latest history will use primary", e)
            true
        }
    }

    private companion object {
        val REPLICA_LAG_MILLIS_SQL =
            """
            SELECT CASE
                WHEN pg_is_in_recovery()
                THEN CASE
                    WHEN pg_last_wal_replay_lsn() IS NULL THEN NULL
                    WHEN pg_last_wal_replay_lsn() >= CAST(? AS pg_lsn) THEN 0
                    WHEN pg_last_xact_replay_timestamp() IS NULL THEN NULL
                    ELSE GREATEST(EXTRACT(EPOCH FROM (clock_timestamp() - pg_last_xact_replay_timestamp())) * 1000, 0)::bigint
                END
                ELSE 0
            END
            """.trimIndent()
    }
}
