package com.chat.persistence.service

import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.model.MessageType
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
                limit = 10_000,
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
        assertTrue(csv.contains("msg-1,client-1,10,100"))
    }

    private fun message(): AdminMessageDto {
        return AdminMessageDto(
            messageId = "msg-1",
            clientMessageId = "client-1",
            roomId = 10L,
            roomSeq = 100L,
            writeShard = 0,
            senderId = 7L,
            senderUsername = "sender",
            senderDisplayName = "Sender",
            messageType = MessageType.TEXT,
            content = "hello",
            isDeleted = false,
            createdAt = Instant.parse("2026-06-14T00:00:00Z"),
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
