package com.chat.core.admin.port

import com.chat.core.dto.AdminMessageCursor
import com.chat.core.dto.AdminMessageDto
import com.chat.core.dto.AdminMessageSearchCursor
import com.chat.core.dto.AdminMessageSearchMode
import com.chat.core.dto.AdminRoomPolicyUpdateRequest
import com.chat.core.dto.AdminRoomStatusDto
import java.time.Instant

interface AdminMessageStore {
    fun findRoomMessages(criteria: AdminRoomMessageQuery): List<AdminMessageDto>

    fun searchMessages(criteria: AdminMessageQuery): List<AdminMessageDto>

    fun findRoomStatus(roomId: Long): AdminRoomStatusDto

    fun updateRoomPolicy(roomId: Long, request: AdminRoomPolicyUpdateRequest): AdminRoomStatusDto
}

data class AdminMessageQuery(
    val query: String,
    val searchMode: AdminMessageSearchMode,
    val roomId: Long?,
    val from: Instant?,
    val to: Instant?,
    val senderId: Long?,
    val cursor: AdminMessageSearchCursor?,
    val limit: Int,
)

data class AdminRoomMessageQuery(
    val roomId: Long,
    val from: Instant?,
    val to: Instant?,
    val cursor: AdminMessageCursor?,
    val limit: Int,
    val senderId: Long? = null,
)
