package com.chat.persistence.repository

import com.chat.core.room.port.ChatRoomStore
import com.chat.domain.model.ChatRoom
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class ChatRoomStoreAdapter(private val repository: ChatRoomRepository) : ChatRoomStore {
    override fun findById(roomId: Long): ChatRoom? = repository.findById(roomId).orElse(null)

    override fun findByIdForMembershipUpdate(roomId: Long): ChatRoom? = repository.findByIdForMembershipUpdate(roomId)

    override fun save(room: ChatRoom): ChatRoom = repository.save(room)

    override fun findUserChatRooms(userId: Long, pageable: Pageable): Page<ChatRoom> = repository.findUserChatRooms(userId, pageable)

    override fun findByIsActiveTrueOrderByCreatedAtDesc(): List<ChatRoom> = repository.findByIsActiveTrueOrderByCreatedAtDesc()

    override fun findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(name: String): List<ChatRoom> =
        repository.findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(name)
}
