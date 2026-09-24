package com.chat.core.room.port

import com.chat.domain.model.ChatRoom
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface ChatRoomStore {
    fun findById(roomId: Long): ChatRoom?

    fun findByIdForMembershipUpdate(roomId: Long): ChatRoom?

    fun save(room: ChatRoom): ChatRoom

    fun findUserChatRooms(userId: Long, pageable: Pageable): Page<ChatRoom>

    fun findByIsActiveTrueOrderByCreatedAtDesc(): List<ChatRoom>

    fun findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(name: String): List<ChatRoom>
}
