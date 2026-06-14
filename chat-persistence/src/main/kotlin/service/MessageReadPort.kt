package com.chat.persistence.service

import com.chat.domain.dto.MessageDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface MessageReadPort {
    fun findPageByRoom(roomId: Long, pageable: Pageable): Page<MessageDto>
    fun findLatestMessages(roomId: Long, limit: Int): List<MessageDto>
    fun findMessagesBefore(roomId: Long, cursor: Long, limit: Int): List<MessageDto>
    fun findMessagesAfter(roomId: Long, cursor: Long, limit: Int): List<MessageDto>
    fun findGapMessages(roomId: Long, afterSeq: Long, limit: Int): List<MessageDto>
    fun findLatestMessage(roomId: Long): MessageDto?
    fun findByClientMessageId(roomId: Long, senderId: Long, clientMessageId: String): MessageDto?
}
