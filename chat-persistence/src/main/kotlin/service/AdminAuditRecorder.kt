package com.chat.persistence.service

import com.chat.persistence.repository.AdminAuditLogRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class AdminAuditRecorder(
    private val auditLogRepository: AdminAuditLogRepository,
    private val objectMapper: ObjectMapper,
) {
    fun record(actor: String, action: String, targetType: String, targetId: String, metadata: Any) {
        auditLogRepository.record(
            actor = actor,
            action = action,
            targetType = targetType,
            targetId = targetId,
            metadataJson = objectMapper.writeValueAsString(metadata),
        )
    }
}
