package com.chat.persistence.service

import com.chat.domain.model.MessageType
import com.chat.persistence.repository.PartitionedMessageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime

class PartitionedMessageWriteAdapterTest {

    @Test
    fun `partitioned adapter는 repository insert 결과를 write outcomes로 변환한다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(
            partitionedMessageRepository = repository,
            writeShardResolver = CanonicalWriteShardResolver(FakeRoomStorageConfigReader(4)),
        )
        val requests = listOf(writeRequest("msg-1"), writeRequest("msg-2"))
        `when`(repository.batchInsert(anyRequestList())).thenReturn(listOf(true, false))

        val result = adapter.write(requests)

        assertEquals(1, result.writtenCount)
        assertEquals(listOf(true, false), result.outcomes.map { it.written })
        assertEquals(listOf("msg-1", "msg-2"), result.outcomes.map { it.request.messageId })
    }

    @Test
    fun `partitioned adapter는 빈 요청이면 repository를 호출하지 않는다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(
            partitionedMessageRepository = repository,
            writeShardResolver = CanonicalWriteShardResolver(FakeRoomStorageConfigReader(4)),
        )

        val result = adapter.write(emptyList())

        assertEquals(0, result.writtenCount)
        assertEquals(emptyList<MessageWriteOutcome>(), result.outcomes)
        verifyNoInteractions(repository)
    }

    @Test
    fun `partitioned adapter는 repository 결과 크기가 요청 크기와 다르면 실패한다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(
            partitionedMessageRepository = repository,
            writeShardResolver = CanonicalWriteShardResolver(FakeRoomStorageConfigReader(4)),
        )
        val requests = listOf(writeRequest("msg-1"), writeRequest("msg-2"))
        `when`(repository.batchInsert(anyRequestList())).thenReturn(listOf(true))

        assertThrows(IllegalStateException::class.java) {
            adapter.write(requests)
        }
    }

    @Test
    fun `partitioned adapter는 canonical writeShard를 room storage config 기준으로 재계산한다`() {
        val repository = mock(PartitionedMessageRepository::class.java)
        val adapter = PartitionedMessageWriteAdapter(
            partitionedMessageRepository = repository,
            writeShardResolver = CanonicalWriteShardResolver(FakeRoomStorageConfigReader(16)),
        )
        val request = writeRequest("msg-1").copy(writeShard = 999, streamShard = 3, fanoutShard = 5)
        `when`(repository.batchInsert(anyRequestList())).thenReturn(listOf(true))

        adapter.write(listOf(request))

        val requestCaptor = requestListCaptor()
        verify(repository).batchInsert(captureRequestList(requestCaptor))
        val insertedRequest = requestCaptor.value.single()
        assertEquals(Math.floorMod("msg-1".hashCode(), 16), insertedRequest.writeShard)
        assertEquals(3, insertedRequest.streamShard)
        assertEquals(5, insertedRequest.fanoutShard)
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

    @Suppress("UNCHECKED_CAST")
    private fun requestListCaptor(): ArgumentCaptor<List<MessageWriteRequest>> {
        return ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<MessageWriteRequest>>
    }

    private fun anyRequestList(): List<MessageWriteRequest> {
        org.mockito.ArgumentMatchers.anyList<MessageWriteRequest>()
        return uninitialized()
    }

    private fun captureRequestList(captor: ArgumentCaptor<List<MessageWriteRequest>>): List<MessageWriteRequest> {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T

    private class FakeRoomStorageConfigReader(
        private val shardCount: Int,
    ) : RoomStorageConfigReader {
        override fun currentShardCount(roomId: Long): Int = shardCount
    }
}
