package com.chat.domain.dto

import com.chat.domain.model.MessageType
import java.time.Instant
import java.time.LocalDateTime

data class AdminMessageDto(
    val messageId: String,
    val clientMessageId: String?,
    val roomId: Long,
    val roomSeq: Long,
    val writeShard: Int,
    val senderId: Long,
    val senderUsername: String,
    val senderDisplayName: String,
    val messageType: MessageType,
    val content: String?,
    val isDeleted: Boolean,
    val createdAt: Instant,
)

data class AdminMessageHistoryRequest(
    val roomId: Long,
    val from: Instant?,
    val to: Instant?,
    val cursor: String?,
    val limit: Int,
)

enum class AdminMessageSearchMode {
    FTS,
    CONTAINS,
}

data class AdminMessageSearchRequest(
    val query: String,
    val searchMode: AdminMessageSearchMode = AdminMessageSearchMode.FTS,
    val roomId: Long?,
    val from: Instant?,
    val to: Instant?,
    val senderId: Long?,
    val cursor: String?,
    val limit: Int,
)

data class AdminMessagePageResponse(
    val messages: List<AdminMessageDto>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val latencyMs: Long,
)

data class AdminMessageSearchResponse(
    val query: String,
    val messages: List<AdminMessageDto>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val latencyMs: Long,
)

data class AdminRoomStatusDto(
    val roomId: Long,
    val heatLevel: String,
    val liveFeedMaxMessages: Int,
    val liveFeedMaxAgeSeconds: Int,
    val rateLimitPerSecond: Int?,
    val slowModeSeconds: Int?,
    val replicaLagMs: Long?,
    val searchP95LatencyMs: Long?,
    val userRateLimitPerSecond: Int? = null,
    val autoPolicyEnabled: Boolean = true,
    val moderatorPriority: Boolean = true,
)

data class AdminRoomPolicyUpdateRequest(
    val heatLevel: String? = null,
    val liveFeedMaxMessages: Int? = null,
    val liveFeedMaxAgeSeconds: Int? = null,
    val rateLimitPerSecond: Int? = null,
    val userRateLimitPerSecond: Int? = null,
    val slowModeSeconds: Int? = null,
    val clearRateLimit: Boolean? = null,
    val clearUserRateLimit: Boolean? = null,
    val clearSlowMode: Boolean? = null,
    val autoPolicyEnabled: Boolean? = null,
    val moderatorPriority: Boolean? = null,
)

data class AdminExportMessagesRequest(
    val roomId: Long?,
    val from: Instant?,
    val to: Instant?,
    val query: String?,
    val senderId: Long?,
)

data class AdminExportJobDto(
    val jobId: String,
    val status: String,
    val createdAt: LocalDateTime,
)
