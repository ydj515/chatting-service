package com.chat.admin.controller

import com.chat.admin.config.AdminProperties
import com.chat.admin.security.AdminTokenVerifier
import com.chat.domain.dto.AdminExportJobDto
import com.chat.domain.dto.AdminExportMessagesRequest
import com.chat.domain.dto.AdminMessageHistoryRequest
import com.chat.domain.dto.AdminMessagePageResponse
import com.chat.domain.dto.AdminMessageSearchRequest
import com.chat.domain.dto.AdminMessageSearchResponse
import com.chat.domain.dto.AdminMessageSearchMode
import com.chat.domain.dto.AdminRoomStatusDto
import com.chat.domain.service.AdminChatService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

@RestController
@RequestMapping("/admin")
class AdminChatController(
    private val adminTokenVerifier: AdminTokenVerifier,
    private val adminChatService: AdminChatService,
    private val adminProperties: AdminProperties,
) {

    @GetMapping("/chat-rooms/{roomId}/messages")
    fun getRoomMessages(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable roomId: Long,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false) limit: Int?,
    ): AdminMessagePageResponse {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminChatService.getRoomMessages(
            actor = actor,
            request = AdminMessageHistoryRequest(
                roomId = roomId,
                from = parseDateTime(from),
                to = parseDateTime(to),
                cursor = cursor,
                limit = boundedLimit(limit),
            ),
        )
    }

    @GetMapping("/messages/search")
    fun searchMessages(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestParam("q", required = false, defaultValue = "") query: String,
        @RequestParam(required = false) roomId: Long?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) senderId: Long?,
        @RequestParam(required = false) mode: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false) limit: Int?,
    ): AdminMessageSearchResponse {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminChatService.searchMessages(
            actor = actor,
            request = AdminMessageSearchRequest(
                query = query,
                searchMode = parseSearchMode(mode),
                roomId = roomId,
                from = parseDateTime(from),
                to = parseDateTime(to),
                senderId = senderId,
                cursor = cursor,
                limit = boundedLimit(limit),
            ),
        )
    }

    @GetMapping("/rooms/{roomId}/status")
    fun getRoomStatus(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable roomId: Long,
    ): AdminRoomStatusDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminChatService.getRoomStatus(actor = actor, roomId = roomId)
    }

    @PostMapping("/exports/messages")
    fun createMessageExport(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestBody request: AdminExportMessagesHttpRequest,
    ): ResponseEntity<AdminExportJobDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        validateExportRequest(request)
        val job = adminChatService.createMessageExport(
            actor = actor,
            request = AdminExportMessagesRequest(
                roomId = request.roomId,
                from = parseDateTime(request.from),
                to = parseDateTime(request.to),
                query = request.query,
                senderId = request.senderId,
            ),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job)
    }

    private fun validateExportRequest(request: AdminExportMessagesHttpRequest) {
        if (request.roomId == null && request.query.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "roomId or query is required for admin message export",
            )
        }
    }

    private fun boundedLimit(limit: Int?): Int {
        val maxLimit = adminProperties.maxLimit.coerceAtLeast(1)
        return (limit ?: adminProperties.defaultLimit).coerceIn(1, maxLimit)
    }

    private fun parseDateTime(value: String?): Instant? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atZone(DEFAULT_TIME_ZONE).toInstant() }
            .getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid date format: $value",
                )
            }
    }

    private fun parseSearchMode(value: String?): AdminMessageSearchMode {
        if (value.isNullOrBlank()) {
            return AdminMessageSearchMode.FTS
        }
        return runCatching { AdminMessageSearchMode.valueOf(value.trim().uppercase()) }
            .getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported admin search mode: $value",
                )
            }
    }

    data class AdminExportMessagesHttpRequest(
        val roomId: Long? = null,
        val from: String? = null,
        val to: String? = null,
        val query: String? = null,
        val senderId: Long? = null,
    )

    private companion object {
        const val ADMIN_TOKEN_HEADER = "X-Admin-Token"
        val DEFAULT_TIME_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
