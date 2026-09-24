package com.chat.core.room.port

import com.chat.domain.model.ChatRoomMember

interface ChatMembershipStore {
    fun save(member: ChatRoomMember)

    fun findByChatRoomIdAndIsActiveTrue(roomId: Long): List<ChatRoomMember>

    fun findByChatRoomIdAndUserIdAndIsActiveTrue(roomId: Long, userId: Long): ChatRoomMember?

    fun existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId: Long, userId: Long): Boolean

    fun countActiveMembersInRoom(roomId: Long): Long

    fun countActiveMembersByRooms(roomIds: Collection<Long>): Map<Long, Long>

    fun reactivateMembership(roomId: Long, userId: Long): Int

    fun leaveChatRoom(roomId: Long, userId: Long)
}
