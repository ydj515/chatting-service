package com.chat.persistence.service

import com.chat.domain.model.MessageType
import com.chat.persistence.repository.PartitionedMessageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.LocalDateTime

class PartitionedMessageWriteAdapterTest {

    @Test
    fun `partitioned adapter는 repository insert 결과를 write outcomes로 변환한다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(repository)
        val requests = listOf(writeRequest("msg-1"), writeRequest("msg-2"))
        `when`(repository.batchInsert(requests)).thenReturn(listOf(true, false))

        val result = adapter.write(requests)

        assertEquals(1, result.writtenCount)
        assertEquals(listOf(true, false), result.outcomes.map { it.written })
        assertEquals(requests, result.outcomes.map { it.request })
    }

    @Test
    fun `partitioned adapter는 빈 요청이면 repository를 호출하지 않는다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(repository)

        val result = adapter.write(emptyList())

        assertEquals(0, result.writtenCount)
        assertEquals(emptyList<MessageWriteOutcome>(), result.outcomes)
        verifyNoInteractions(repository)
    }

    @Test
    fun `partitioned adapter는 repository 결과 크기가 요청 크기와 다르면 실패한다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(repository)
        val requests = listOf(writeRequest("msg-1"), writeRequest("msg-2"))
        `when`(repository.batchInsert(requests)).thenReturn(listOf(true))

        assertThrows(IllegalStateException::class.java) {
            adapter.write(requests)
        }
    }

    private fun writeRequest(messageId: String): MessageWriteRequest {
        return MessageWriteRequest(
            messageId = messageId,
            clientMessageId = "client-1",
            chatRoomId = 10L,
            senderId = 7L,
            messageType = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 11L,
            roomSeq = 11L,
            streamShard = 0,
            writeShard = 1,
            fanoutShard = 2,
            createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
        )
    }
}
