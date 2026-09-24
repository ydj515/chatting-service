package com.chat.core.admin.port

import com.chat.core.dto.AdminExportJobDto
import com.chat.core.dto.AdminExportMessagesRequest
import java.time.LocalDateTime

interface AdminExportStore {
    fun create(actor: String, request: AdminExportMessagesRequest): AdminExportJobDto

    fun findById(jobId: String): AdminExportState?
}

data class AdminExportState(
    val jobId: String,
    val status: String,
    val outputUri: String?,
    val exportedRows: Int,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
)
