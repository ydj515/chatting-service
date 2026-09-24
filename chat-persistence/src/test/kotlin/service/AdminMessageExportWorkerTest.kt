package com.chat.persistence.service

import com.chat.core.dto.AdminMessageCursor
import com.chat.core.dto.AdminMessageCursorCodec
import com.chat.core.dto.AdminMessageDto
import com.chat.core.dto.AdminMessageSearchMode
import com.chat.domain.model.MessageType
import com.chat.persistence.config.AdminExportProperties
import com.chat.persistence.config.ChatObjectStorageProperties
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.repository.AdminExportJobRecord
import com.chat.persistence.repository.AdminExportJobRepository
import com.chat.persistence.repository.AdminMessageQuery
import com.chat.persistence.repository.AdminMessageRepository
import com.chat.persistence.repository.AdminRoomMessageQuery
import com.chat.persistence.storage.ObjectStoragePort
import com.chat.persistence.storage.ObjectUploadRequest
import com.chat.persistence.storage.ObjectUploadResult
import com.chat.persistence.storage.PresignedObjectUrl
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
import java.time.Duration
import java.time.Instant

class AdminMessageExportWorkerTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_TEST_POSTGRES_URL", matches = ".+")
    fun `room export filters sender across cursor chunks without requiring a search query`(@TempDir tempDir: Path) {
        val connection = java.sql.DriverManager.getConnection(System.getenv("CHAT_TEST_POSTGRES_URL"), "postgres", System.getenv("CHAT_TEST_POSTGRES_PASSWORD").orEmpty())
        val source = org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)
        try {
            val jdbc = org.springframework.jdbc.core.JdbcTemplate(source)
            jdbc.execute("CREATE TEMP TABLE app_users (id bigint, username text, display_name text)")
            jdbc.execute("INSERT INTO app_users VALUES (7, 'seven', 'Seven'), (8, 'eight', 'Eight')")
            jdbc.execute("CREATE TEMP TABLE chat_messages (message_id text, client_message_id text, room_id bigint, room_seq bigint, write_shard int, sender_id bigint, message_type text, content text, is_deleted boolean, created_at timestamptz)")
            repeat(6) { index ->
                jdbc.update("INSERT INTO chat_messages VALUES (?, NULL, 10, ?, 0, ?, 'TEXT', 'hello', false, '2026-06-14T00:00:00Z')", "msg-$index", index, if (index % 2 == 0) 7 else 8)
            }
            val messages = AdminMessageRepository(jdbc, jdbc)
            for (senderId in listOf(7L, null)) {
                val jobs = mock(AdminExportJobRepository::class.java)
                val storage = RecordingObjectStoragePort()
                `when`(jobs.claimNextPending("worker-1")).thenReturn(AdminExportJobRecord("export-$senderId", "admin", """{"roomId":10,"query":null,"senderId":$senderId}"""))
                val count = worker(jobs, messages, tempDir, storage).pollAndExport()
                val expected = if (senderId == null) listOf("msg-5", "msg-4", "msg-3", "msg-2", "msg-1", "msg-0") else listOf("msg-4", "msg-2", "msg-0")
                assertEquals(expected.size, count)
                assertEquals(expected, requireNotNull(storage.uploadedContent).lineSequence().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(',') }.toList())
            }
        } finally {
            source.destroy()
        }
    }

    @Test
    fun `checkpoint sees complete bytes before writer closes and resume keeps those rows`(@TempDir tempDir: Path) {
        val jobs = mock(AdminExportJobRepository::class.java)
        val messages = mock(AdminMessageRepository::class.java)
        val storage = RecordingObjectStoragePort()
        val first = message()
        val cursor = AdminMessageCursor(first.createdAt, first.roomSeq, first.messageId)
        val token = AdminMessageCursorCodec.encode(cursor)
        val output = tempDir.resolve("export-flush.csv")
        val job = AdminExportJobRecord(jobId = "export-flush", actor = "admin", requestJson = """{"roomId":10}""")
        `when`(jobs.claimNextPending("worker-1")).thenReturn(job)
        `when`(messages.findRoomMessages(AdminRoomMessageQuery(10, null, null, null, 2))).thenReturn(listOf(first))
        var checkpointBytes = ""
        org.mockito.Mockito.doAnswer {
            checkpointBytes = Files.readString(output)
            assertTrue(checkpointBytes.contains("msg-1,client-100,10,100"))
            error("stop after checkpoint")
        }.`when`(jobs).updateCheckpoint("export-flush", token, 1, output.toUri().toString())
        assertEquals(0, worker(jobs, messages, tempDir, storage).pollAndExport())
        assertTrue(checkpointBytes.isNotEmpty())

        `when`(jobs.claimNextPending("worker-1")).thenReturn(job.copy(cursorToken = token, exportedRows = 1, outputUri = output.toUri().toString()))
        `when`(messages.findRoomMessages(AdminRoomMessageQuery(10, null, null, cursor, 2))).thenReturn(emptyList())
        assertEquals(1, worker(jobs, messages, tempDir, storage).pollAndExport())
        assertEquals(checkpointBytes, storage.uploadedContent)
    }

    @Test
    fun `export worker는 pending job을 claim하고 CSV 파일을 완료 상태로 기록한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val storage = RecordingObjectStoragePort()
        val worker = worker(exportJobRepository, messageRepository, tempDir, objectStoragePort = storage)
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":"2026-06-14T00:00:00Z","to":null,"query":null,"senderId":null}""",
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                AdminRoomMessageQuery(
                    roomId = 10L,
                    from = Instant.parse("2026-06-14T00:00:00Z"),
                    to = null,
                    cursor = null,
                    limit = 2,
                ),
            ),
        ).thenReturn(listOf(message()))

        val exportedRows = worker.pollAndExport()

        assertEquals(1, exportedRows)
        val outputCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(exportJobRepository).markCompleted(
            eqString("export-1"),
            captureString(outputCaptor),
        )
        assertEquals("s3://chat-archives/admin-exports/export-1.csv", outputCaptor.value)
        assertEquals("admin-exports/export-1.csv", storage.uploadedObjectKey)
        assertEquals("text/csv; charset=utf-8", storage.uploadedContentType)
        // 업로드 후 로컬 staging 파일은 삭제되어야 한다.
        assertTrue(Files.notExists(requireNotNull(storage.uploadedFile)))
        val csv = requireNotNull(storage.uploadedContent)
        assertTrue(csv.contains("messageId,clientMessageId,roomId"))
        assertTrue(csv.contains("msg-1,client-100,10,100"))
    }

    @Test
    fun `export worker는 spreadsheet formula injection을 방지한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val storage = RecordingObjectStoragePort()
        val worker = worker(exportJobRepository, messageRepository, tempDir, objectStoragePort = storage)
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":null,"to":null,"query":null,"senderId":null}""",
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                AdminRoomMessageQuery(
                    roomId = 10L,
                    from = null,
                    to = null,
                    cursor = null,
                    limit = 2,
                ),
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
        assertEquals("s3://chat-archives/admin-exports/export-1.csv", outputCaptor.value)
        val csv = requireNotNull(storage.uploadedContent)
        assertTrue(csv.contains("'\u0040admin"))
        assertTrue(csv.contains("\"'=IMPORTXML(\"\"https://example.test\"\")\""))
    }

    @Test
    fun `export worker는 room history를 chunk cursor로 반복 조회한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val worker = worker(exportJobRepository, messageRepository, tempDir)
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
                AdminRoomMessageQuery(
                    roomId = 10L,
                    from = null,
                    to = null,
                    cursor = null,
                    limit = 2,
                ),
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-100", roomSeq = 100L, createdAt = Instant.parse("2026-06-14T00:00:02Z")),
                message(messageId = "msg-99", roomSeq = 99L, createdAt = Instant.parse("2026-06-14T00:00:01Z")),
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                AdminRoomMessageQuery(
                    roomId = 10L,
                    from = null,
                    to = null,
                    cursor = firstChunkCursor,
                    limit = 2,
                ),
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-98", roomSeq = 98L, createdAt = Instant.parse("2026-06-14T00:00:00Z")),
            ),
        )

        val exportedRows = worker.pollAndExport()

        assertEquals(3, exportedRows)
        verify(messageRepository).findRoomMessages(AdminRoomMessageQuery(10L, null, null, null, 2))
        verify(messageRepository).findRoomMessages(AdminRoomMessageQuery(10L, null, null, firstChunkCursor, 2))
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
        val worker = worker(exportJobRepository, messageRepository, tempDir)
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
                AdminMessageQuery(
                    query = "hello",
                    searchMode = AdminMessageSearchMode.FTS,
                    roomId = 10L,
                    from = null,
                    to = null,
                    senderId = 7L,
                    cursor = null,
                    limit = 2,
                ),
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-100", roomSeq = 100L, createdAt = Instant.parse("2026-06-14T00:00:02Z")),
                message(messageId = "msg-99", roomSeq = 99L, createdAt = Instant.parse("2026-06-14T00:00:01Z")),
            ),
        )
        `when`(
            messageRepository.searchMessages(
                AdminMessageQuery(
                    query = "hello",
                    searchMode = AdminMessageSearchMode.FTS,
                    roomId = 10L,
                    from = null,
                    to = null,
                    senderId = 7L,
                    cursor = firstChunkCursor,
                    limit = 2,
                ),
            ),
        ).thenReturn(emptyList())

        val exportedRows = worker.pollAndExport()

        assertEquals(2, exportedRows)
        verify(messageRepository).searchMessages(AdminMessageQuery("hello", AdminMessageSearchMode.FTS, 10L, null, null, 7L, null, 2))
        verify(messageRepository).searchMessages(AdminMessageQuery("hello", AdminMessageSearchMode.FTS, 10L, null, null, 7L, firstChunkCursor, 2))
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
        val storage = RecordingObjectStoragePort()
        val worker = worker(exportJobRepository, messageRepository, tempDir, objectStoragePort = storage)
        val output = tempDir.resolve("export-1.csv")
        Files.writeString(
            output,
            "messageId,clientMessageId,roomId,roomSeq,writeShard,senderId,senderUsername," +
                "senderDisplayName,messageType,content,isDeleted,createdAt\n" +
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
                AdminRoomMessageQuery(
                    roomId = 10L,
                    from = null,
                    to = null,
                    cursor = checkpointCursor,
                    limit = 2,
                ),
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-98", roomSeq = 98L, createdAt = Instant.parse("2026-06-14T00:00:00Z")),
            ),
        )

        val exportedRows = worker.pollAndExport()

        assertEquals(3, exportedRows)
        // 완료 후 로컬 staging 파일은 삭제되므로 업로드된 내용으로 누적 append 결과를 검증한다.
        assertTrue(Files.notExists(output))
        val csv = requireNotNull(storage.uploadedContent)
        assertEquals(1, Regex("messageId,clientMessageId,roomId").findAll(csv).count())
        assertTrue(csv.contains("msg-98,client-98,10,98"))
        verify(messageRepository).findRoomMessages(AdminRoomMessageQuery(10L, null, null, checkpointCursor, 2))
        verify(exportJobRepository).updateCheckpoint(
            eqString("export-1"),
            eqString(AdminMessageCursorCodec.encode(finalCursor)),
            eq(3),
            eqString(output.toUri().toString()),
        )
    }

    @Test
    fun `export worker는 object storage upload 실패 시 job을 failed로 전이한다`(
        @TempDir tempDir: Path,
    ) {
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        val messageRepository = mock(AdminMessageRepository::class.java)
        val storage = RecordingObjectStoragePort().apply { failUpload = true }
        val worker = worker(exportJobRepository, messageRepository, tempDir, objectStoragePort = storage)
        `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
            AdminExportJobRecord(
                jobId = "export-1",
                actor = "admin-local",
                requestJson = """{"roomId":10,"from":null,"to":null,"query":null,"senderId":null}""",
            ),
        )
        `when`(
            messageRepository.findRoomMessages(
                AdminRoomMessageQuery(
                    roomId = 10L,
                    from = null,
                    to = null,
                    cursor = null,
                    limit = 2,
                ),
            ),
        ).thenReturn(listOf(message()))

        val exportedRows = worker.pollAndExport()

        assertEquals(0, exportedRows)
        verify(exportJobRepository).markFailed(eqString("export-1"), eqString("upload failed"))
    }

    private fun worker(
        exportJobRepository: AdminExportJobRepository,
        messageRepository: AdminMessageRepository,
        tempDir: Path,
        objectStoragePort: ObjectStoragePort = RecordingObjectStoragePort(),
    ): AdminMessageExportWorker =
        AdminMessageExportWorker(
            exportJobRepository = exportJobRepository,
            messageRepository = messageRepository,
            workerProperties = ChatWorkerProperties(consumerName = "worker-1"),
            objectMapper = testObjectMapper(),
            exportProperties = AdminExportProperties(directory = tempDir.toString(), chunkSize = 2),
            exportStorage = AdminExportStorage(objectStoragePort, ChatObjectStorageProperties()),
        )

    private class RecordingObjectStoragePort : ObjectStoragePort {
        var uploadedFile: Path? = null
        var uploadedContent: String? = null
        var uploadedObjectKey: String? = null
        var uploadedContentType: String? = null
        var failUpload: Boolean = false

        override fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult {
            if (failUpload) {
                error("upload failed")
            }
            uploadedFile = request.file
            // 실제 업로드처럼 파일 바이트를 읽어 둔다. worker가 업로드 후 로컬 파일을 삭제하므로
            // 테스트는 원본 경로가 아닌 캡처한 내용으로 CSV를 검증한다.
            uploadedContent = Files.readString(request.file, Charsets.UTF_8)
            uploadedObjectKey = request.objectKey
            uploadedContentType = request.contentType
            return ObjectUploadResult("s3://chat-archives/${request.objectKey}")
        }

        override fun createDownloadUrl(
            objectUri: String,
            ttl: Duration,
        ): PresignedObjectUrl =
            PresignedObjectUrl(
                url = "http://localhost/download",
                expiresAt = Instant.parse("2026-06-26T00:15:00Z"),
            )
    }

    private fun message(
        messageId: String = "msg-1",
        roomSeq: Long = 100L,
        createdAt: Instant = Instant.parse("2026-06-14T00:00:00Z"),
        senderDisplayName: String = "Sender",
        content: String = "hello",
    ): AdminMessageDto =
        AdminMessageDto(
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

    private fun testObjectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

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
