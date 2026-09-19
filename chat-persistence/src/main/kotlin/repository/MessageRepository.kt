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
    @Query(
        """
        SELECT m FROM Message m JOIN FETCH m.sender JOIN FETCH m.chatRoom
        WHERE m.chatRoom.id IN :roomIds AND m.isDeleted = false AND NOT EXISTS (
            SELECT newer.id FROM Message newer WHERE newer.chatRoom.id = m.chatRoom.id AND newer.isDeleted = false AND (
                CASE WHEN newer.roomSeq > 0 THEN newer.roomSeq ELSE newer.sequenceNumber END > CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END
                OR (CASE WHEN newer.roomSeq > 0 THEN newer.roomSeq ELSE newer.sequenceNumber END = CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END AND newer.id > m.id)
            )
        )
    """,
    )
    fun findLatestMessagesByRooms(roomIds: Collection<Long>): List<Message>

    fun findByMessageId(messageId: String): Optional<Message>

    @Query(
        """
        SELECT m FROM Message m
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId
        AND m.sender.id = :senderId
        AND m.clientMessageId = :clientMessageId
    """,
    )
    fun findByChatRoomIdAndSenderIdAndClientMessageId(
        chatRoomId: Long,
        senderId: Long,
        clientMessageId: String,
    ): Optional<Message>

    @Query(
        """
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false 
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END DESC
    """,
    )
    fun findByChatRoomId(chatRoomId: Long, pageable: Pageable): Page<Message>

    // 커서 기반 페이지네이션 - 이전 메시지들 (과거 방향)
    @Query(
        """
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId 
        AND m.isDeleted = false 
        AND CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END < :cursor
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END DESC
    """,
    )
    fun findMessagesBefore(chatRoomId: Long, cursor: Long, pageable: Pageable): List<Message>

    // 커서 기반 페이지네이션 - 이후 메시지들 (최신 방향)
    @Query(
        """
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId 
        AND m.isDeleted = false 
        AND CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END > :cursor
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END ASC
    """,
    )
    fun findMessagesAfter(chatRoomId: Long, cursor: Long, pageable: Pageable): List<Message>

    // 최신 메시지들 (커서 없을 때)
    @Query(
        """
        SELECT m FROM Message m 
        JOIN FETCH m.sender s
        JOIN FETCH m.chatRoom cr
        WHERE m.chatRoom.id = :chatRoomId 
        AND m.isDeleted = false 
        ORDER BY CASE WHEN m.roomSeq > 0 THEN m.roomSeq ELSE m.sequenceNumber END DESC
    """,
    )
    fun findLatestMessages(chatRoomId: Long, pageable: Pageable): List<Message>

    // 네이티브 쿼리로 최신 메시지 1개 조회 (캐시 가능)
    @Query(
        value = """
        SELECT * FROM messages m 
        WHERE m.chat_room_id = :chatRoomId AND m.is_deleted = false 
        ORDER BY CASE WHEN m.room_seq > 0 THEN m.room_seq ELSE m.sequence_number END DESC
        LIMIT 1
    """,
        nativeQuery = true,
    )
    fun findLatestMessage(chatRoomId: Long): Message?
}
