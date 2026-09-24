package com.chat.core.service

import com.chat.core.dto.AdminExportJobDto
import com.chat.core.dto.AdminExportJobStatusDto
import com.chat.core.dto.AdminExportMessagesRequest
import com.chat.core.dto.AdminMessageHistoryRequest
import com.chat.core.dto.AdminMessagePageResponse
import com.chat.core.dto.AdminMessageSearchRequest
import com.chat.core.dto.AdminMessageSearchResponse
import com.chat.core.dto.AdminRoomPolicyUpdateRequest
import com.chat.core.dto.AdminRoomStatusDto

interface AdminChatService {
    fun getRoomMessages(actor: String, request: AdminMessageHistoryRequest): AdminMessagePageResponse

    fun searchMessages(actor: String, request: AdminMessageSearchRequest): AdminMessageSearchResponse

    fun getRoomStatus(actor: String, roomId: Long): AdminRoomStatusDto

    fun updateRoomPolicy(actor: String, roomId: Long, request: AdminRoomPolicyUpdateRequest): AdminRoomStatusDto

    fun createMessageExport(actor: String, request: AdminExportMessagesRequest): AdminExportJobDto

    fun getMessageExport(actor: String, jobId: String): AdminExportJobStatusDto?
}
