package com.chat.persistence.repository

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.jdbc.core.JdbcTemplate

class AdminAuditLogRepositoryTest {

    @Test
    fun `audit log insert는 actor action target metadata를 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = AdminAuditLogRepository(jdbcTemplate)

        repository.record(
            actor = "admin-local",
            action = "ADMIN_MESSAGE_SEARCH",
            targetType = "MESSAGE",
            targetId = "room:10",
            metadataJson = """{"query":"hello"}""",
        )

        verify(jdbcTemplate).update(
            contains("INSERT INTO admin_audit_logs"),
            eq("admin-local"),
            eq("ADMIN_MESSAGE_SEARCH"),
            eq("MESSAGE"),
            eq("room:10"),
            eq("""{"query":"hello"}"""),
        )
    }
}
