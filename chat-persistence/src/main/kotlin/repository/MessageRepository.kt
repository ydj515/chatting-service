package com.chat.persistence.repository

import com.chat.domain.model.Message
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface MessageRepository : JpaRepository<Message, Long> {

    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId
        AND m.sender.id = :senderId
        AND m.clientMessageId = :clientMessageId
    """)
    fun findByChatRoomIdAndSenderIdAndClientMessageId(
        chatRoomId: Long,
        senderId: Long,
        clientMessageId: String,
    ): Optional<Message>

    @Query("""
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false 
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END DESC, m.createdAt DESC
    """)
    fun findByChatRoomId(chatRoomId: Long, pageable: Pageable): Page<Message>

    // 커서 기반 페이지네이션 - 이전 메시지들 (과거 방향)
    @Query("""
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId 
        AND m.isDeleted = false 
        AND m.id < :cursor
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END DESC, m.createdAt DESC
    """)
    fun findMessagesBefore(chatRoomId: Long, cursor: Long, pageable: Pageable): List<Message>

    // 커서 기반 페이지네이션 - 이후 메시지들 (최신 방향)
    @Query("""
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId 
        AND m.isDeleted = false 
        AND m.id > :cursor
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END ASC, m.createdAt ASC
    """)
    fun findMessagesAfter(chatRoomId: Long, cursor: Long, pageable: Pageable): List<Message>

    // 최신 메시지들 (커서 없을 때)
    @Query("""
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId 
        AND m.isDeleted = false 
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END DESC, m.createdAt DESC
    """)
    fun findLatestMessages(chatRoomId: Long, pageable: Pageable): List<Message>

    // 네이티브 쿼리로 최신 메시지 1개 조회 (캐시 가능)
    @Query(value = """
        SELECT * FROM messages m 
        WHERE m.chat_room_id = :chatRoomId AND m.is_deleted = false 
        ORDER BY CASE WHEN m.room_seq > 0 THEN m.room_seq ELSE m.sequence_number END DESC, m.created_at DESC
        LIMIT 1
    """, nativeQuery = true)
    fun findLatestMessage(chatRoomId: Long): Message?
}
