package com.chat.domain.service

import com.chat.domain.dto.AdminExportJobDto
import com.chat.domain.dto.AdminExportMessagesRequest
import com.chat.domain.dto.AdminMessageHistoryRequest
import com.chat.domain.dto.AdminMessagePageResponse
import com.chat.domain.dto.AdminMessageSearchRequest
import com.chat.domain.dto.AdminMessageSearchResponse
import com.chat.domain.dto.AdminRoomPolicyUpdateRequest
import com.chat.domain.dto.AdminRoomStatusDto

interface AdminChatService {
    fun getRoomMessages(actor: String, request: AdminMessageHistoryRequest): AdminMessagePageResponse

    fun searchMessages(actor: String, request: AdminMessageSearchRequest): AdminMessageSearchResponse

    fun getRoomStatus(actor: String, roomId: Long): AdminRoomStatusDto

    fun updateRoomPolicy(actor: String, roomId: Long, request: AdminRoomPolicyUpdateRequest): AdminRoomStatusDto

    fun createMessageExport(actor: String, request: AdminExportMessagesRequest): AdminExportJobDto
}
