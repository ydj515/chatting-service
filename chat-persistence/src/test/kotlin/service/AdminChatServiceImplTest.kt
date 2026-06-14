package com.chat.persistence.service

import com.chat.domain.dto.AdminExportJobDto
import com.chat.domain.dto.AdminExportMessagesRequest
import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageHistoryRequest
import com.chat.domain.dto.AdminMessageSearchCursor
import com.chat.domain.dto.AdminMessageSearchCursorCodec
import com.chat.domain.dto.AdminMessageSearchRequest
import com.chat.domain.dto.AdminMessageSearchMode
import com.chat.domain.model.MessageType
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.AdminExportJobRepository
import com.chat.persistence.repository.AdminMessageRepository
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.LocalDateTime

class AdminChatServiceImplTest {

    @Test
    fun `history는 limit보다 1건 더 조회해 hasNext와 nextCursor를 계산하고 audit log를 남긴다`() {
        val fixture = fixture()
        `when`(
            fixture.messageRepository.findRoomMessages(
                roomId = 10L,
                from = null,
                to = null,
                cursor = null,
                limit = 3,
            ),
        ).thenReturn(
            listOf(message(roomSeq = 12L), message(roomSeq = 11L), message(roomSeq = 10L)),
        )

        val response = fixture.service.getRoomMessages(
            actor = "admin-local",
            request = AdminMessageHistoryRequest(
                roomId = 10L,
                from = null,
                to = null,
                cursor = null,
                limit = 2,
            ),
        )

        assertEquals(listOf(12L, 11L), response.messages.map { it.roomSeq })
        assertEquals(11L, response.nextCursor)
        assertEquals(true, response.hasNext)
        assertTrue(response.latencyMs >= 0)
        verify(fixture.auditRepository).record(
            eqString("admin-local"),
            eqString("ADMIN_ROOM_MESSAGES"),
            eqString("ROOM"),
            eqString("room:10"),
            containsString(""""roomId":10"""),
        )
    }

    @Test
    fun `search는 결과와 latency를 반환하고 audit log를 남긴다`() {
        val fixture = fixture()
        `when`(
            fixture.messageRepository.searchMessages(
                query = "hello",
                searchMode = AdminMessageSearchMode.CONTAINS,
                roomId = 10L,
                from = null,
                to = null,
                senderId = null,
                cursor = null,
                limit = 2,
            ),
        ).thenReturn(
            listOf(
                message(messageId = "msg-9", roomSeq = 9L, createdAt = Instant.parse("2026-06-14T00:00:09Z")),
                message(messageId = "msg-8", roomSeq = 8L, createdAt = Instant.parse("2026-06-14T00:00:08Z")),
            ),
        )

        val response = fixture.service.searchMessages(
            actor = "admin-local",
            request = AdminMessageSearchRequest(
                query = "hello",
                searchMode = AdminMessageSearchMode.CONTAINS,
                roomId = 10L,
                from = null,
                to = null,
                senderId = null,
                cursor = null,
                limit = 1,
            ),
        )

        assertEquals("hello", response.query)
        assertEquals(listOf(9L), response.messages.map { it.roomSeq })
        assertEquals(
            AdminMessageSearchCursor(
                createdAt = Instant.parse("2026-06-14T00:00:09Z"),
                roomSeq = 9L,
                messageId = "msg-9",
            ),
            AdminMessageSearchCursorCodec.decode(response.nextCursor),
        )
        assertEquals(true, response.hasNext)
        assertTrue(response.latencyMs >= 0)
        verify(fixture.auditRepository).record(
            eqString("admin-local"),
            eqString("ADMIN_MESSAGE_SEARCH"),
            eqString("MESSAGE"),
            eqString("room:10"),
            containsString(""""query":"hello""""),
        )
        verify(fixture.auditRepository).record(
            eqString("admin-local"),
            eqString("ADMIN_MESSAGE_SEARCH"),
            eqString("MESSAGE"),
            eqString("room:10"),
            containsString(""""searchMode":"CONTAINS""""),
        )
    }

    @Test
    fun `search는 opaque cursor를 decode해서 repository로 전달한다`() {
        val fixture = fixture()
        val cursor = AdminMessageSearchCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01Z"),
            roomSeq = 1001L,
            messageId = "msg-1001",
        )
        val encodedCursor = AdminMessageSearchCursorCodec.encode(cursor)
        `when`(
            fixture.messageRepository.searchMessages(
                query = "hello",
                searchMode = AdminMessageSearchMode.FTS,
                roomId = null,
                from = null,
                to = null,
                senderId = null,
                cursor = cursor,
                limit = 51,
            ),
        ).thenReturn(emptyList())

        fixture.service.searchMessages(
            actor = "admin-local",
            request = AdminMessageSearchRequest(
                query = "hello",
                searchMode = AdminMessageSearchMode.FTS,
                roomId = null,
                from = null,
                to = null,
                senderId = null,
                cursor = encodedCursor,
                limit = 50,
            ),
        )

        verify(fixture.messageRepository).searchMessages(
            eqString("hello"),
            eqSearchMode(AdminMessageSearchMode.FTS),
            eq(null),
            eq(null),
            eq(null),
            eq(null),
            eq(cursor),
            eq(51),
        )
    }

    @Test
    fun `export 생성은 export job을 만들고 audit log를 남긴다`() {
        val fixture = fixture()
        `when`(
            fixture.exportJobRepository.create(
                eqString("admin-local"),
                anyStringValue(),
            ),
        ).thenReturn(
            AdminExportJobDto(
                jobId = "export-1",
                status = "PENDING",
                createdAt = LocalDateTime.parse("2026-06-14T00:00:00"),
            ),
        )

        val job = fixture.service.createMessageExport(
            actor = "admin-local",
            request = AdminExportMessagesRequest(
                roomId = 10L,
                from = Instant.parse("2026-06-14T00:00:00Z"),
                to = null,
                query = "hello",
                senderId = null,
            ),
        )

        assertEquals("export-1", job.jobId)
        verify(fixture.auditRepository).record(
            eqString("admin-local"),
            eqString("ADMIN_MESSAGE_EXPORT_REQUESTED"),
            eqString("EXPORT_JOB"),
            eqString("export-1"),
            containsString(""""query":"hello""""),
        )
    }

    private fun fixture(): Fixture {
        val messageRepository = mock(AdminMessageRepository::class.java)
        val auditRepository = mock(AdminAuditLogRepository::class.java)
        val exportJobRepository = mock(AdminExportJobRepository::class.java)
        return Fixture(
            messageRepository = messageRepository,
            auditRepository = auditRepository,
            exportJobRepository = exportJobRepository,
            service = AdminChatServiceImpl(
                messageRepository = messageRepository,
                auditLogRepository = auditRepository,
                exportJobRepository = exportJobRepository,
                objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
            ),
        )
    }

    private fun message(
        roomSeq: Long,
        messageId: String = "msg-$roomSeq",
        createdAt: Instant = Instant.parse("2026-06-14T00:00:00Z"),
    ): AdminMessageDto {
        return AdminMessageDto(
            messageId = messageId,
            clientMessageId = "client-$roomSeq",
            roomId = 10L,
            roomSeq = roomSeq,
            writeShard = 0,
            senderId = 7L,
            senderUsername = "user7",
            senderDisplayName = "User 7",
            messageType = MessageType.TEXT,
            content = "hello",
            isDeleted = false,
            createdAt = createdAt,
        )
    }

    private fun eqString(value: String): String {
        eq(value)
        return uninitialized()
    }

    private fun containsString(value: String): String {
        contains(value)
        return uninitialized()
    }

    private fun eqSearchMode(value: AdminMessageSearchMode): AdminMessageSearchMode {
        eq(value)
        return uninitialized()
    }

    private fun anyStringValue(): String {
        anyString()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T

    private data class Fixture(
        val messageRepository: AdminMessageRepository,
        val auditRepository: AdminAuditLogRepository,
        val exportJobRepository: AdminExportJobRepository,
        val service: AdminChatServiceImpl,
    )
}
