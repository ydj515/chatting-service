package com.chat.core.admin.service

import com.chat.core.admin.port.AdminAudit
import com.chat.core.admin.port.AdminExportState
import com.chat.core.admin.port.AdminExportStore
import com.chat.core.admin.port.AdminMessageStore
import com.chat.core.admin.port.ExportDownloads
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDateTime

class AdminExportStatusTest {
    private val exports = mock(AdminExportStore::class.java)
    private val audit = mock(AdminAudit::class.java)
    private val downloads = mock(ExportDownloads::class.java)
    private val service = AdminChatServiceImpl(mock(AdminMessageStore::class.java), audit, exports, AdminExportStatusReader(exports, audit), downloads)

    @Test
    fun `missing job does not create audit or download side effects`() {
        assertNull(service.getMessageExport("admin", "missing"))
        verifyNoInteractions(audit, downloads)
    }

    @Test
    fun `running staging path is never exposed or signed`() {
        `when`(exports.findById("job")).thenReturn(state("RUNNING", "file:///worker/private.csv"))
        val result = requireNotNull(service.getMessageExport("admin", "job"))
        assertNull(result.outputUri)
        assertNull(result.downloadUrl)
        verify(audit).record("admin", "ADMIN_MESSAGE_EXPORT_VIEWED", "EXPORT_JOB", "job", mapOf("jobId" to "job", "status" to "RUNNING"))
        verifyNoInteractions(downloads)
    }

    @Test
    fun `failed audit prevents download signing`() {
        `when`(exports.findById("job")).thenReturn(state("COMPLETED", "s3://exports/job.csv"))
        doThrow(IllegalStateException("Audit unavailable")).`when`(audit).record(
            "admin", "ADMIN_MESSAGE_EXPORT_VIEWED", "EXPORT_JOB", "job", mapOf("jobId" to "job", "status" to "COMPLETED"),
        )
        assertThrows(IllegalStateException::class.java) { service.getMessageExport("admin", "job") }
        verifyNoInteractions(downloads)
    }

    private fun state(status: String, uri: String) = AdminExportState(
        jobId = "job", status = status, outputUri = uri, exportedRows = 4, errorMessage = null,
        createdAt = LocalDateTime.parse("2026-09-25T00:00:00"), startedAt = null, completedAt = null,
    )
}
