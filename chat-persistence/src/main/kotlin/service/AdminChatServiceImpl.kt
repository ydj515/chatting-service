package com.chat.persistence.service

import com.chat.domain.dto.AdminExportJobDto
import com.chat.domain.dto.AdminExportMessagesRequest
import com.chat.domain.dto.AdminMessageCursor
import com.chat.domain.dto.AdminMessageCursorCodec
import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageHistoryRequest
import com.chat.domain.dto.AdminMessagePageResponse
import com.chat.domain.dto.AdminMessageSearchCursor
import com.chat.domain.dto.AdminMessageSearchCursorCodec
import com.chat.domain.dto.AdminMessageSearchRequest
import com.chat.domain.dto.AdminMessageSearchResponse
import com.chat.domain.dto.AdminRoomPolicyUpdateRequest
import com.chat.domain.dto.AdminRoomStatusDto
import com.chat.domain.service.AdminChatService
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.AdminExportJobRepository
import com.chat.persistence.repository.AdminMessageRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.system.measureNanoTime

@Service
class AdminChatServiceImpl(
    private val messageRepository: AdminMessageRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val exportJobRepository: AdminExportJobRepository,
    private val objectMapper: ObjectMapper,
) : AdminChatService {

    override fun getRoomMessages(
        actor: String,
        request: AdminMessageHistoryRequest,
    ): AdminMessagePageResponse {
        var response: AdminMessagePageResponse
        val elapsedNanos = measureNanoTime {
            val rows = messageRepository.findRoomMessages(
                roomId = request.roomId,
                from = request.from,
                to = request.to,
                cursor = AdminMessageCursorCodec.decode(request.cursor),
                limit = request.limit + 1,
            )
            response = rows.toMessagePage(request.limit, 0)
        }
        val finalResponse = response.copy(latencyMs = elapsedNanos.toMillis())
        auditLogRepository.record(
            actor = actor,
            action = "ADMIN_ROOM_MESSAGES",
            targetType = "ROOM",
            targetId = "room:${request.roomId}",
            metadataJson = objectMapper.writeValueAsString(request),
        )
        return finalResponse
    }

    override fun searchMessages(
        actor: String,
        request: AdminMessageSearchRequest,
    ): AdminMessageSearchResponse {
        var response: AdminMessageSearchResponse
        val elapsedNanos = measureNanoTime {
            val rows = messageRepository.searchMessages(
                query = request.query,
                searchMode = request.searchMode,
                roomId = request.roomId,
                from = request.from,
                to = request.to,
                senderId = request.senderId,
                cursor = AdminMessageSearchCursorCodec.decode(request.cursor),
                limit = request.limit + 1,
            )
            val messages = rows.take(request.limit)
            response = AdminMessageSearchResponse(
                query = request.query,
                messages = messages,
                nextCursor = if (rows.size > request.limit) messages.lastOrNull()?.toSearchCursor() else null,
                hasNext = rows.size > request.limit,
                latencyMs = 0,
            )
        }
        val finalResponse = response.copy(latencyMs = elapsedNanos.toMillis())
        auditLogRepository.record(
            actor = actor,
            action = "ADMIN_MESSAGE_SEARCH",
            targetType = "MESSAGE",
            targetId = request.roomId?.let { "room:$it" } ?: "global",
            metadataJson = objectMapper.writeValueAsString(request),
        )
        return finalResponse
    }

    override fun getRoomStatus(actor: String, roomId: Long): AdminRoomStatusDto {
        val status = messageRepository.findRoomStatus(roomId)
        auditLogRepository.record(
            actor = actor,
            action = "ADMIN_ROOM_STATUS",
            targetType = "ROOM",
            targetId = "room:$roomId",
            metadataJson = """{"roomId":$roomId}""",
        )
        return status
    }

    @Transactional
    @CacheEvict(value = ["roomAdmissionPolicies"], key = "#roomId")
    override fun updateRoomPolicy(
        actor: String,
        roomId: Long,
        request: AdminRoomPolicyUpdateRequest,
    ): AdminRoomStatusDto {
        val status = messageRepository.updateRoomPolicy(roomId = roomId, request = request)
        auditLogRepository.record(
            actor = actor,
            action = "ADMIN_ROOM_POLICY_UPDATED",
            targetType = "ROOM",
            targetId = "room:$roomId",
            metadataJson = objectMapper.writeValueAsString(request),
        )
        return status
    }

    override fun createMessageExport(
        actor: String,
        request: AdminExportMessagesRequest,
    ): AdminExportJobDto {
        val requestJson = objectMapper.writeValueAsString(request)
        val job = exportJobRepository.create(actor = actor, requestJson = requestJson)
        auditLogRepository.record(
            actor = actor,
            action = "ADMIN_MESSAGE_EXPORT_REQUESTED",
            targetType = "EXPORT_JOB",
            targetId = job.jobId,
            metadataJson = requestJson,
        )
        return job
    }

    private fun List<com.chat.domain.dto.AdminMessageDto>.toMessagePage(
        limit: Int,
        latencyMs: Long,
    ): AdminMessagePageResponse {
        val messages = take(limit)
        return AdminMessagePageResponse(
            messages = messages,
            nextCursor = if (size > limit) messages.lastOrNull()?.toAdminMessageCursor() else null,
            hasNext = size > limit,
            latencyMs = latencyMs,
        )
    }

    private fun Long.toMillis(): Long = this / 1_000_000

    private fun AdminMessageDto.toSearchCursor(): String {
        return AdminMessageSearchCursorCodec.encode(
            AdminMessageSearchCursor(
                createdAt = createdAt,
                roomSeq = roomSeq,
                messageId = messageId,
            ),
        )
    }

    private fun AdminMessageDto.toAdminMessageCursor(): String {
        return AdminMessageCursorCodec.encode(
            AdminMessageCursor(
                createdAt = createdAt,
                roomSeq = roomSeq,
                messageId = messageId,
            ),
        )
    }
}
