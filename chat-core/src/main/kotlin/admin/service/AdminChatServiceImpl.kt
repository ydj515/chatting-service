package com.chat.core.admin.service

import com.chat.core.admin.port.AdminAudit
import com.chat.core.admin.port.AdminExportStore
import com.chat.core.admin.port.AdminMessageQuery
import com.chat.core.admin.port.AdminMessageStore
import com.chat.core.admin.port.AdminRoomMessageQuery
import com.chat.core.admin.port.ExportDownloads
import com.chat.core.dto.AdminExportJobDto
import com.chat.core.dto.AdminExportJobStatusDto
import com.chat.core.dto.AdminExportMessagesRequest
import com.chat.core.dto.AdminMessageCursor
import com.chat.core.dto.AdminMessageCursorCodec
import com.chat.core.dto.AdminMessageDto
import com.chat.core.dto.AdminMessageHistoryRequest
import com.chat.core.dto.AdminMessagePageResponse
import com.chat.core.dto.AdminMessageSearchCursor
import com.chat.core.dto.AdminMessageSearchCursorCodec
import com.chat.core.dto.AdminMessageSearchRequest
import com.chat.core.dto.AdminMessageSearchResponse
import com.chat.core.dto.AdminRoomPolicyUpdateRequest
import com.chat.core.dto.AdminRoomStatusDto
import com.chat.core.service.AdminChatService
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.system.measureNanoTime

@Service
@Transactional // 조회도 감사 기록을 함께 저장한다. 다운로드 URL 생성은 별도 경계에서 수행한다.
class AdminChatServiceImpl(
    private val messageRepository: AdminMessageStore,
    private val auditRecorder: AdminAudit,
    private val exportJobRepository: AdminExportStore,
    private val exportStatusReader: AdminExportStatusReader,
    private val exportDownloads: ExportDownloads,
) : AdminChatService {
    override fun getRoomMessages(
        actor: String,
        request: AdminMessageHistoryRequest,
    ): AdminMessagePageResponse {
        var response: AdminMessagePageResponse
        val elapsedNanos = measureNanoTime {
            val rows = messageRepository.findRoomMessages(
                AdminRoomMessageQuery(
                    roomId = request.roomId,
                    from = request.from,
                    to = request.to,
                    cursor = AdminMessageCursorCodec.decode(request.cursor),
                    limit = request.limit + 1,
                ),
            )
            response = rows.toMessagePage(request.limit, 0)
        }
        val finalResponse = response.copy(latencyMs = elapsedNanos.toMillis())
        auditRecorder.record(
            actor = actor,
            action = "ADMIN_ROOM_MESSAGES",
            targetType = "ROOM",
            targetId = "room:${request.roomId}",
            metadata = request,
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
                AdminMessageQuery(
                    query = request.query,
                    searchMode = request.searchMode,
                    roomId = request.roomId,
                    from = request.from,
                    to = request.to,
                    senderId = request.senderId,
                    cursor = AdminMessageSearchCursorCodec.decode(request.cursor),
                    limit = request.limit + 1,
                ),
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
        auditRecorder.record(
            actor = actor,
            action = "ADMIN_MESSAGE_SEARCH",
            targetType = "MESSAGE",
            targetId = request.roomId?.let { "room:$it" } ?: "global",
            metadata = request,
        )
        return finalResponse
    }

    override fun getRoomStatus(actor: String, roomId: Long): AdminRoomStatusDto {
        val status = messageRepository.findRoomStatus(roomId)
        auditRecorder.record(
            actor = actor,
            action = "ADMIN_ROOM_STATUS",
            targetType = "ROOM",
            targetId = "room:$roomId",
            metadata = mapOf("roomId" to roomId),
        )
        return status
    }

    @CacheEvict(value = ["roomAdmissionPolicies"], key = "#roomId")
    override fun updateRoomPolicy(
        actor: String,
        roomId: Long,
        request: AdminRoomPolicyUpdateRequest,
    ): AdminRoomStatusDto {
        val status = messageRepository.updateRoomPolicy(roomId = roomId, request = request)
        auditRecorder.record(
            actor = actor,
            action = "ADMIN_ROOM_POLICY_UPDATED",
            targetType = "ROOM",
            targetId = "room:$roomId",
            metadata = request,
        )
        return status
    }

    override fun createMessageExport(
        actor: String,
        request: AdminExportMessagesRequest,
    ): AdminExportJobDto {
        val job = exportJobRepository.create(actor = actor, request = request)
        auditRecorder.record(
            actor = actor,
            action = "ADMIN_MESSAGE_EXPORT_REQUESTED",
            targetType = "EXPORT_JOB",
            targetId = job.jobId,
            metadata = request,
        )
        return job
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun getMessageExport(actor: String, jobId: String): AdminExportJobStatusDto? {
        val status = exportStatusReader.load(actor, jobId) ?: return null
        val download = status.outputUri?.let { exportDownloads.createDownloadUrl(it) }
        return status.copy(downloadUrl = download?.url, downloadUrlExpiresAt = download?.expiresAt)
    }

    private fun List<com.chat.core.dto.AdminMessageDto>.toMessagePage(
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

    private fun AdminMessageDto.toSearchCursor(): String =
        AdminMessageSearchCursorCodec.encode(
            AdminMessageSearchCursor(
                createdAt = createdAt,
                roomSeq = roomSeq,
                messageId = messageId,
            ),
        )

    private fun AdminMessageDto.toAdminMessageCursor(): String =
        AdminMessageCursorCodec.encode(
            AdminMessageCursor(
                createdAt = createdAt,
                roomSeq = roomSeq,
                messageId = messageId,
            ),
        )
}
