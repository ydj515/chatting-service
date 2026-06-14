package com.chat.domain.dto

import com.chat.domain.model.MessageType
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
    val createdAt: LocalDateTime,
)

data class AdminMessageHistoryRequest(
    val roomId: Long,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val cursor: Long?,
    val limit: Int,
)

data class AdminMessageSearchRequest(
    val query: String,
    val roomId: Long?,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val senderId: Long?,
    val cursor: Long?,
    val limit: Int,
)

data class AdminMessagePageResponse(
    val messages: List<AdminMessageDto>,
    val nextCursor: Long?,
    val hasNext: Boolean,
    val latencyMs: Long,
)

data class AdminMessageSearchResponse(
    val query: String,
    val messages: List<AdminMessageDto>,
    val nextCursor: Long?,
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
)

data class AdminExportMessagesRequest(
    val roomId: Long?,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val query: String?,
    val senderId: Long?,
)

data class AdminExportJobDto(
    val jobId: String,
    val status: String,
    val createdAt: LocalDateTime,
)
