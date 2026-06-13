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
) : LatestHistoryReadRoutingPolicy {
    private val logger = LoggerFactory.getLogger(ReadReplicaLagPolicy::class.java)

    override fun usePrimaryForLatestHistory(): Boolean {
        if (!properties.enabled) {
            return false
        }

        return try {
            val lagMillis = messageReadJdbcTemplate.queryForObject(REPLICA_LAG_MILLIS_SQL, Long::class.java) ?: 0L
            lagMillis > properties.latestHistoryMaxReplicaLag.toMillis()
        } catch (e: Exception) {
            logger.warn("Failed to measure read replica lag; latest history will use primary", e)
            true
        }
    }

    private companion object {
        val REPLICA_LAG_MILLIS_SQL = """
            SELECT CASE
                WHEN pg_is_in_recovery()
                THEN COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())) * 1000, 0)::bigint
                ELSE 0
            END
        """.trimIndent()
    }
}
