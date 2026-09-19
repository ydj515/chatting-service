package com.chat.persistence.repository

import com.chat.domain.model.ChatRoomMember
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ChatRoomMemberRepository : CrudRepository<ChatRoomMember, Long> {
    // memberToDto 에서 member.user 에 접근하므로 User 를 함께 fetch 해 N+1 을 방지한다.
    @EntityGraph(attributePaths = ["user"])
    fun findByChatRoomIdAndIsActiveTrue(chatRoomId: Long): List<ChatRoomMember>

    @Query("SELECT m.user.id FROM ChatRoomMember m WHERE m.chatRoom.id = :roomId AND m.user.id IN :userIds AND m.isActive = true")
    fun findActiveUserIds(roomId: Long, userIds: List<Long>): List<Long>

    fun findByChatRoomIdAndUserIdAndIsActiveTrue(chatRoomId: Long, userId: Long): ChatRoomMember?

    @Query("SELECT COUNT(crm) FROM ChatRoomMember crm WHERE crm.chatRoom.id = :chatRoomId AND crm.isActive = true")
    fun countActiveMembersInRoom(chatRoomId: Long): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ChatRoomMember m SET m.isActive = true, m.role = com.chat.domain.model.MemberRole.MEMBER, m.leftAt = null, m.joinedAt = CURRENT_TIMESTAMP WHERE m.chatRoom.id = :roomId AND m.user.id = :userId AND m.isActive = false")
    fun reactivateMembership(roomId: Long, userId: Long): Int

    @Query("SELECT m.chatRoom.id AS roomId, COUNT(m) AS memberCount FROM ChatRoomMember m WHERE m.chatRoom.id IN :roomIds AND m.isActive = true GROUP BY m.chatRoom.id")
    fun countActiveMembersByRooms(roomIds: Collection<Long>): List<RoomMemberCount>

    @Modifying
    @Query(
        """
        UPDATE ChatRoomMember crm 
        SET crm.isActive = false, crm.leftAt = CURRENT_TIMESTAMP 
        WHERE crm.chatRoom.id = :chatRoomId AND crm.user.id = :userId
    """,
    )
    fun leaveChatRoom(chatRoomId: Long, userId: Long)

    /*
        1. A라는 Entity를 가져 왓다.
        2. A에 매칭이 되는 Raw를 수정을 하였습니다. -> DB를 수정
        3. A에 대한 로그를 찍으면 X가 노출
     */

    fun existsByChatRoomIdAndUserIdAndIsActiveTrue(chatRoomId: Long, userId: Long): Boolean
}

interface RoomMemberCount {
    val roomId: Long
    val memberCount: Long
}
