package com.chat.persistence.service

import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageCursor
import com.chat.domain.dto.AdminMessageCursorCodec
import com.chat.domain.model.MessageType
import com.chat.domain.dto.AdminMessageSearchMode
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.repository.AdminExportJobRecord
import com.chat.persistence.repository.AdminExportJobRepository
import com.chat.persistence.repository.AdminMessageRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AdminMessageExportWorkerTest {

    @Test
    fun `export worker는 pending job을 claim하고 CSV 파일을 완료 상태로 기록한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val objectMapper = testObjectMapper()
        val worker = AdminMessageExportWorker(
            exportJobRepository = exportJobRepository,
            messageRepository = messageRepository,
            workerProperties = ChatWorkerProperties(consumerName = "worker-1"),
            objectMapper = objectMapper,
            exportDirectory = tempDir.toString(),
            exportChunkSize = 2,
        )
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":"2026-06-14T00:00:00Z","to":null,"query":null,"senderId":null}""",
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                roomId = 10L,
                from = Instant.parse("2026-06-14T00:00:00Z"),
                to = null,
                cursor = null,
                limit = 2,
            ),
        ).thenReturn(listOf(message()))

        val exportedRows = worker.pollAndExport()

        assertEquals(1, exportedRows)
        val outputCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(exportJobRepository).markCompleted(
            eqString("export-1"),
            captureString(outputCaptor),
        )
        assertTrue(outputCaptor.value.startsWith("file://"))
        val csvPath = Path.of(java.net.URI.create(outputCaptor.value))
        assertTrue(Files.exists(csvPath))
        val csv = Files.readString(csvPath, Charsets.UTF_8)
        assertTrue(csv.contains("messageId,clientMessageId,roomId"))
        assertTrue(csv.contains("msg-1,client-100,10,100"))
    }

    @Test
    fun `export worker는 spreadsheet formula injection을 방지한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val worker = AdminMessageExportWorker(
            exportJobRepository = exportJobRepository,
            messageRepository = messageRepository,
            workerProperties = ChatWorkerProperties(consumerName = "worker-1"),
            objectMapper = testObjectMapper(),
            exportDirectory = tempDir.toString(),
            exportChunkSize = 2,
        )
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":null,"to":null,"query":null,"senderId":null}""",
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                roomId = 10L,
                from = null,
                to = null,
                cursor = null,
                limit = 2,
            ),
        ).thenReturn(
            listOf(
                message(
                    senderDisplayName = "@admin",
                    content = "=IMPORTXML(\"https://example.test\")",
                ),
            ),
        )

        worker.pollAndExport()

        val outputCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(exportJobRepository).markCompleted(
            eqString("export-1"),
            captureString(outputCaptor),
        )
        val csvPath = Path.of(java.net.URI.create(outputCaptor.value))
        val csv = Files.readString(csvPath, Charsets.UTF_8)
        assertTrue(csv.contains("'\u0040admin"))
        assertTrue(csv.contains("\"'=IMPORTXML(\"\"https://example.test\"\")\""))
    }

    @Test
    fun `export worker는 room history를 chunk cursor로 반복 조회한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val worker = AdminMessageExportWorker(
            exportJobRepository = exportJobRepository,
            messageRepository = messageRepository,
            workerProperties = ChatWorkerProperties(consumerName = "worker-1"),
            objectMapper = testObjectMapper(),
            exportDirectory = tempDir.toString(),
            exportChunkSize = 2,
        )
        val firstChunkCursor = AdminMessageCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01Z"),
            roomSeq = 99L,
            messageId = "msg-99",
        )
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":null,"to":null,"query":null,"senderId":null}""",
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                roomId = 10L,
                from = null,
                to = null,
                cursor = null,
                limit = 2,
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-100", roomSeq = 100L, createdAt = Instant.parse("2026-06-14T00:00:02Z")),
                message(messageId = "msg-99", roomSeq = 99L, createdAt = Instant.parse("2026-06-14T00:00:01Z")),
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                roomId = 10L,
                from = null,
                to = null,
                cursor = firstChunkCursor,
                limit = 2,
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-98", roomSeq = 98L, createdAt = Instant.parse("2026-06-14T00:00:00Z")),
            ),
        )

        val exportedRows = worker.pollAndExport()

        assertEquals(3, exportedRows)
        verify(messageRepository).findRoomMessages(10L, null, null, null, 2)
        verify(messageRepository).findRoomMessages(10L, null, null, firstChunkCursor, 2)
        verify(exportJobRepository).updateCheckpoint(
            eqString("export-1"),
            eqString(AdminMessageCursorCodec.encode(firstChunkCursor)),
            eq(2),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    @Test
    fun `export worker는 keyword search도 cursor chunk로 반복 조회한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val worker = AdminMessageExportWorker(
            exportJobRepository = exportJobRepository,
            messageRepository = messageRepository,
            workerProperties = ChatWorkerProperties(consumerName = "worker-1"),
            objectMapper = testObjectMapper(),
            exportDirectory = tempDir.toString(),
            exportChunkSize = 2,
        )
        val firstChunkCursor = AdminMessageCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01Z"),
            roomSeq = 99L,
            messageId = "msg-99",
        )
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":null,"to":null,"query":"hello","senderId":7}""",
            ),
        )
        `when`(
            messageRepository.searchMessages(
                query = "hello",
                searchMode = AdminMessageSearchMode.FTS,
                roomId = 10L,
                from = null,
                to = null,
                senderId = 7L,
                cursor = null,
                limit = 2,
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-100", roomSeq = 100L, createdAt = Instant.parse("2026-06-14T00:00:02Z")),
                message(messageId = "msg-99", roomSeq = 99L, createdAt = Instant.parse("2026-06-14T00:00:01Z")),
            ),
        )
        `when`(
            messageRepository.searchMessages(
                query = "hello",
                searchMode = AdminMessageSearchMode.FTS,
                roomId = 10L,
                from = null,
                to = null,
                senderId = 7L,
                cursor = firstChunkCursor,
                limit = 2,
            ),
        ).thenReturn(emptyList())

        val exportedRows = worker.pollAndExport()

        assertEquals(2, exportedRows)
        verify(messageRepository).searchMessages("hello", AdminMessageSearchMode.FTS, 10L, null, null, 7L, null, 2)
        verify(messageRepository).searchMessages("hello", AdminMessageSearchMode.FTS, 10L, null, null, 7L, firstChunkCursor, 2)
        verify(exportJobRepository).updateCheckpoint(
            eqString("export-1"),
            eqString(AdminMessageCursorCodec.encode(firstChunkCursor)),
            eq(2),
            org.mockito.ArgumentMatchers.anyString(),
        )
        verify(exportJobRepository, times(1)).markCompleted(eqString("export-1"), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `export worker는 requeued job의 checkpoint cursor와 output 파일에서 이어 쓴다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val worker = AdminMessageExportWorker(
            exportJobRepository = exportJobRepository,
            messageRepository = messageRepository,
            workerProperties = ChatWorkerProperties(consumerName = "worker-1"),
            objectMapper = testObjectMapper(),
            exportDirectory = tempDir.toString(),
            exportChunkSize = 2,
        )
        val output = tempDir.resolve("export-1.csv")
        Files.writeString(
            output,
            "messageId,clientMessageId,roomId,roomSeq,writeShard,senderId,senderUsername,senderDisplayName,messageType,content,isDeleted,createdAt\n" +
                "msg-100,client-100,10,100,0,7,sender,Sender,TEXT,hello,false,2026-06-14T00:00:02Z\n" +
                "msg-99,client-99,10,99,0,7,sender,Sender,TEXT,hello,false,2026-06-14T00:00:01Z\n",
            Charsets.UTF_8,
        )
        val checkpointCursor = AdminMessageCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01Z"),
            roomSeq = 99L,
            messageId = "msg-99",
        )
        val finalCursor = AdminMessageCursor(
            createdAt = Instant.parse("2026-06-14T00:00:00Z"),
            roomSeq = 98L,
            messageId = "msg-98",
        )
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":null,"to":null,"query":null,"senderId":null}""",
                cursorToken = AdminMessageCursorCodec.encode(checkpointCursor),
                exportedRows = 2,
                outputUri = output.toUri().toString(),
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                roomId = 10L,
                from = null,
                to = null,
                cursor = checkpointCursor,
                limit = 2,
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-98", roomSeq = 98L, createdAt = Instant.parse("2026-06-14T00:00:00Z")),
            ),
        )

        val exportedRows = worker.pollAndExport()

        assertEquals(3, exportedRows)
        val csv = Files.readString(output, Charsets.UTF_8)
        assertEquals(1, Regex("messageId,clientMessageId,roomId").findAll(csv).count())
        assertTrue(csv.contains("msg-98,client-98,10,98"))
        verify(messageRepository).findRoomMessages(10L, null, null, checkpointCursor, 2)
        verify(exportJobRepository).updateCheckpoint(
            eqString("export-1"),
            eqString(AdminMessageCursorCodec.encode(finalCursor)),
            eq(3),
            eqString(output.toUri().toString()),
        )
    }

    private fun message(
        messageId: String = "msg-1",
        roomSeq: Long = 100L,
        createdAt: Instant = Instant.parse("2026-06-14T00:00:00Z"),
        senderDisplayName: String = "Sender",
        content: String = "hello",
    ): AdminMessageDto {
        return AdminMessageDto(
            messageId = messageId,
            clientMessageId = "client-$roomSeq",
            roomId = 10L,
            roomSeq = roomSeq,
            writeShard = 0,
            senderId = 7L,
            senderUsername = "sender",
            senderDisplayName = senderDisplayName,
            messageType = MessageType.TEXT,
            content = content,
            isDeleted = false,
            createdAt = createdAt,
        )
    }

    private fun testObjectMapper(): ObjectMapper {
        return jacksonObjectMapper().registerModule(JavaTimeModule())
    }

    private fun eqString(value: String): String {
        eq(value)
        return uninitialized()
    }

    private fun captureString(captor: ArgumentCaptor<String>): String {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
