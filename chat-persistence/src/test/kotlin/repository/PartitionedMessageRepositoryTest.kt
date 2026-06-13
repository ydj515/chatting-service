package com.chat.persistence.repository

import com.chat.domain.model.MessageType
import com.chat.persistence.service.MessageWriteRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime

class PartitionedMessageRepositoryTest {

    @Test
    fun `batchInsert는 chat_messages에 batch insert하고 insert count를 written 여부로 변환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = PartitionedMessageRepository(jdbcTemplate)
        `when`(jdbcTemplate.batchUpdate(anyString(), anyBatchSetter())).thenReturn(intArrayOf(1, 0))

        val results = repository.batchInsert(listOf(writeRequest("msg-1"), writeRequest("msg-2")))

        assertEquals(listOf(true, false), results)

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        val setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter::class.java)
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), setterCaptor.capture())
        assertTrue(sqlCaptor.value.contains("INSERT INTO chat_messages"))
        assertTrue(sqlCaptor.value.contains("ON CONFLICT DO NOTHING"))
        assertEquals(2, setterCaptor.value.batchSize)
    }

    @Test
    fun `batchInsert는 request 필드를 canonical table 컬럼 순서에 맞게 바인딩한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = PartitionedMessageRepository(jdbcTemplate)
        val setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter::class.java)
        `when`(jdbcTemplate.batchUpdate(anyString(), captureBatchSetter(setterCaptor))).thenReturn(intArrayOf(1))

        repository.batchInsert(listOf(writeRequest("msg-1")))

        val preparedStatement = mock(PreparedStatement::class.java)
        setterCaptor.value.setValues(preparedStatement, 0)

        verify(preparedStatement).setString(1, "msg-1")
        verify(preparedStatement).setString(2, "client-1")
        verify(preparedStatement).setLong(3, 10L)
        verify(preparedStatement).setLong(4, 11L)
        verify(preparedStatement).setInt(5, 1)
        verify(preparedStatement).setLong(6, 7L)
        verify(preparedStatement).setString(7, MessageType.TEXT.name)
        verify(preparedStatement).setString(8, "hello")
        verify(preparedStatement).setTimestamp(9, Timestamp.valueOf(LocalDateTime.parse("2026-06-13T12:00:00")))
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

    private fun anyString(): String {
        org.mockito.ArgumentMatchers.anyString()
        return uninitialized()
    }

    private fun anyBatchSetter(): BatchPreparedStatementSetter {
        org.mockito.ArgumentMatchers.any(BatchPreparedStatementSetter::class.java)
        return uninitialized()
    }

    private fun captureBatchSetter(captor: ArgumentCaptor<BatchPreparedStatementSetter>): BatchPreparedStatementSetter {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
