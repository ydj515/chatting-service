package com.chat.persistence.service

import com.chat.core.admin.port.AdminAudit
import com.chat.core.admin.port.AdminExportState
import com.chat.core.admin.port.AdminExportStore
import com.chat.core.admin.port.AdminMessageStore
import com.chat.core.admin.port.ExportDownload
import com.chat.core.admin.port.ExportDownloads
import com.chat.core.admin.service.AdminChatServiceImpl
import com.chat.core.admin.service.AdminExportStatusReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class AdminExportDownloadTransactionTest {
    private val source = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    private val manager = DataSourceTransactionManager(source)
    private val jdbc = JdbcTemplate(source)

    @Test
    fun `download signing follows committed audit and suspends the caller transaction`() {
        jdbc.execute("CREATE TABLE export_audit (job_id VARCHAR(100))")
        val exports = mock(AdminExportStore::class.java)
        `when`(exports.findById("job")).thenReturn(AdminExportState("job", "COMPLETED", "s3://exports/job.csv", 4, null, LocalDateTime.now(), null, null))
        val audit = object : AdminAudit {
            override fun record(actor: String, action: String, targetType: String, targetId: String, metadata: Any) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                jdbc.update("INSERT INTO export_audit (job_id) VALUES (?)", targetId)
            }
        }
        val downloads = object : ExportDownloads {
            override fun createDownloadUrl(objectUri: String): ExportDownload {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM export_audit", Int::class.java))
                return ExportDownload("https://download.test/job.csv", Instant.parse("2026-09-25T01:00:00Z"))
            }
        }
        val reader = proxy(AdminExportStatusReader(exports, audit)) as AdminExportStatusReader
        val service = proxy(AdminChatServiceImpl(mock(AdminMessageStore::class.java), audit, exports, reader, downloads)) as AdminChatServiceImpl
        TransactionTemplate(manager).executeWithoutResult { status ->
            val result = requireNotNull(service.getMessageExport("admin", "job"))
            assertEquals("https://download.test/job.csv", result.downloadUrl)
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            status.setRollbackOnly()
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM export_audit", Int::class.java))
    }

    private fun proxy(target: Any): Any = ProxyFactory(target).apply {
        isProxyTargetClass = true
        addAdvice(
            TransactionInterceptor().apply {
                transactionManager = manager
                transactionAttributeSource = AnnotationTransactionAttributeSource()
            },
        )
    }.proxy
}
