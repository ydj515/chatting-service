package com.chat.persistence.service

import com.chat.domain.model.MessageType
import com.chat.persistence.repository.CanonicalMessageRecord
import com.chat.persistence.repository.PartitionedMessageReadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

class PartitionedMessageReadAdapterTest {

    @Test
    fun `partitioned read adapter는 canonical record를 MessageDto로 변환한다`() {
        val repository = mock(PartitionedMessageReadRepository::class.java)
        val adapter = PartitionedMessageReadAdapter(repository)
        val pageable = PageRequest.of(0, 50)
        `when`(repository.findPageByRoom(10L, pageable)).thenReturn(PageImpl(listOf(record()), pageable, 1))

        val page = adapter.findPageByRoom(10L, pageable)

        val message = page.content.single()
        assertEquals(123L, message.id)
        assertEquals("msg-123", message.messageId)
        assertEquals("client-123", message.clientMessageId)
        assertEquals(10L, message.chatRoomId)
        assertEquals(7L, message.sender.id)
        assertEquals("user7", message.sender.username)
        assertEquals("User 7", message.sender.displayName)
        assertEquals(MessageType.TEXT, message.type)
        assertEquals("hello", message.content)
        assertEquals(false, message.isEdited)
        assertEquals(false, message.isDeleted)
        assertEquals(123L, message.sequenceNumber)
        assertEquals(123L, message.roomSeq)
        assertEquals(0, message.streamShard)
        assertEquals(3, message.writeShard)
        assertEquals(0, message.fanoutShard)
    }

    private fun record(): CanonicalMessageRecord {
        return CanonicalMessageRecord(
            messageId = "msg-123",
            clientMessageId = "client-123",
            roomId = 10L,
            roomSeq = 123L,
            writeShard = 3,
            senderId = 7L,
            senderUsername = "user7",
            senderDisplayName = "User 7",
            senderProfileImageUrl = null,
            senderStatus = null,
            senderIsActive = true,
            senderLastSeenAt = null,
            senderCreatedAt = LocalDateTime.parse("2026-06-13T11:00:00"),
            messageType = MessageType.TEXT,
            content = "hello",
            isDeleted = false,
            createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
        )
    }
}
