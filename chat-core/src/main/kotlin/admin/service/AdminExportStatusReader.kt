package com.chat.core.admin.service

import com.chat.core.admin.port.AdminAudit
import com.chat.core.admin.port.AdminExportStore
import com.chat.core.dto.AdminExportJobStatusDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminExportStatusReader(private val exports: AdminExportStore, private val audit: AdminAudit) {
    @Transactional
    fun load(actor: String, jobId: String): AdminExportJobStatusDto? {
        val record = exports.findById(jobId) ?: return null
        val completedObjectUri = record.outputUri?.takeIf { record.status == "COMPLETED" && it.startsWith("s3://") }
        audit.record(actor, "ADMIN_MESSAGE_EXPORT_VIEWED", "EXPORT_JOB", jobId, mapOf("jobId" to jobId, "status" to record.status))
        return AdminExportJobStatusDto(
            jobId = record.jobId,
            status = record.status,
            createdAt = record.createdAt,
            startedAt = record.startedAt,
            completedAt = record.completedAt,
            exportedRows = record.exportedRows,
            outputUri = completedObjectUri,
            downloadUrl = null,
            downloadUrlExpiresAt = null,
            errorMessage = record.errorMessage,
        )
    }
}
