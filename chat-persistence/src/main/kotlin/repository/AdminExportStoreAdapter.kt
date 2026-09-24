package com.chat.persistence.repository

import com.chat.core.admin.port.AdminExportState
import com.chat.core.admin.port.AdminExportStore
import com.chat.core.dto.AdminExportJobDto
import com.chat.core.dto.AdminExportMessagesRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository

@Repository
class AdminExportStoreAdapter(private val repository: AdminExportJobRepository, private val objectMapper: ObjectMapper) : AdminExportStore {
    override fun create(actor: String, request: AdminExportMessagesRequest): AdminExportJobDto = repository.create(actor, objectMapper.writeValueAsString(request))

    override fun findById(jobId: String): AdminExportState? = repository.findById(jobId)?.let {
        AdminExportState(it.jobId, it.status, it.outputUri, it.exportedRows, it.errorMessage, it.createdAt, it.startedAt, it.completedAt)
    }
}
