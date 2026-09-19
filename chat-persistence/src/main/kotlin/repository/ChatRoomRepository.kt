package com.chat.persistence.repository

import com.chat.domain.model.ChatRoom
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ChatRoomRepository : JpaRepository<ChatRoom, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cr FROM ChatRoom cr WHERE cr.id = :roomId")
    fun findByIdForMembershipUpdate(roomId: Long): ChatRoom?

    // chatRoomToDto 에서 chatRoom.createdBy(LAZY) 에 접근하므로 방마다 User 를 다시 조회하는 N+1 이 발생한다.
    // createdBy 는 to-one 연관이라 페이징과 충돌하지 않으므로 @EntityGraph 로 함께 fetch 한다.
    @EntityGraph(attributePaths = ["createdBy"])
    @Query(
        """
        SELECT DISTINCT cr FROM ChatRoom cr
        JOIN ChatRoomMember crm ON cr.id = crm.chatRoom.id
        WHERE crm.user.id = :userId AND crm.isActive = true AND cr.isActive = true
        ORDER BY cr.updatedAt DESC
    """,
    )
    fun findUserChatRooms(userId: Long, pageable: Pageable): Page<ChatRoom>

    // 모든 활성 채팅방 조회 (최신순)
    @EntityGraph(attributePaths = ["createdBy"])
    fun findByIsActiveTrueOrderByCreatedAtDesc(): List<ChatRoom>

    // 이름으로 채팅방 검색 (대소문자 무시, 활성 채팅방만)
    @EntityGraph(attributePaths = ["createdBy"])
    fun findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(name: String): List<ChatRoom>
}
