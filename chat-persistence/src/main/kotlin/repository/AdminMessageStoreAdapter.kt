package com.chat.persistence.repository

import com.chat.core.admin.port.AdminMessageQuery
import com.chat.core.admin.port.AdminMessageStore
import com.chat.core.admin.port.AdminRoomMessageQuery
import com.chat.core.dto.AdminMessageDto
import com.chat.core.dto.AdminRoomPolicyUpdateRequest
import com.chat.core.dto.AdminRoomStatusDto
import org.springframework.stereotype.Repository

@Repository
class AdminMessageStoreAdapter(private val repository: AdminMessageRepository) : AdminMessageStore {
    override fun findRoomMessages(criteria: AdminRoomMessageQuery): List<AdminMessageDto> = repository.findRoomMessages(criteria)

    override fun searchMessages(criteria: AdminMessageQuery): List<AdminMessageDto> = repository.searchMessages(criteria)

    override fun findRoomStatus(roomId: Long): AdminRoomStatusDto = repository.findRoomStatus(roomId)

    override fun updateRoomPolicy(roomId: Long, request: AdminRoomPolicyUpdateRequest): AdminRoomStatusDto = repository.updateRoomPolicy(roomId, request)
}
