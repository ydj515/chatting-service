package com.chat.persistence.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AdminAuditLogRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    fun record(
        actor: String,
        action: String,
        targetType: String,
        targetId: String?,
        metadataJson: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO admin_audit_logs (
                actor,
                action,
                target_type,
                target_id,
                metadata
            )
            VALUES (?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            actor,
            action,
            targetType,
            targetId,
            metadataJson,
        )
    }
}
