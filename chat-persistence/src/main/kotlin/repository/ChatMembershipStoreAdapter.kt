package com.chat.persistence.repository

import com.chat.core.room.port.ChatMembershipStore
import com.chat.domain.model.ChatRoomMember
import org.springframework.stereotype.Repository

@Repository
class ChatMembershipStoreAdapter(private val repository: ChatRoomMemberRepository) : ChatMembershipStore {
    override fun save(member: ChatRoomMember) {
        repository.save(member)
    }

    override fun findByChatRoomIdAndIsActiveTrue(roomId: Long): List<ChatRoomMember> = repository.findByChatRoomIdAndIsActiveTrue(roomId)

    override fun findByChatRoomIdAndUserIdAndIsActiveTrue(roomId: Long, userId: Long): ChatRoomMember? =
        repository.findByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)

    override fun existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId: Long, userId: Long): Boolean =
        repository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)

    override fun countActiveMembersInRoom(roomId: Long): Long = repository.countActiveMembersInRoom(roomId)

    override fun countActiveMembersByRooms(roomIds: Collection<Long>): Map<Long, Long> =
        repository.countActiveMembersByRooms(roomIds).associate { it.roomId to it.memberCount }

    override fun reactivateMembership(roomId: Long, userId: Long): Int = repository.reactivateMembership(roomId, userId)

    override fun leaveChatRoom(roomId: Long, userId: Long) = repository.leaveChatRoom(roomId, userId)
}
