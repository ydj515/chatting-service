package com.chat.admin.controller

import com.chat.admin.config.AdminProperties
import com.chat.admin.security.AdminTokenVerifier
import com.chat.domain.dto.AdminExportJobDto
import com.chat.domain.dto.AdminExportMessagesRequest
import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageHistoryRequest
import com.chat.domain.dto.AdminMessagePageResponse
import com.chat.domain.dto.AdminMessageSearchRequest
import com.chat.domain.dto.AdminMessageSearchResponse
import com.chat.domain.dto.AdminMessageSearchMode
import com.chat.domain.dto.AdminRoomStatusDto
import com.chat.domain.model.MessageType
import com.chat.domain.service.AdminChatService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.time.LocalDateTime

class AdminChatControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var service: RecordingAdminChatService

    @BeforeEach
    fun setUp() {
        service = RecordingAdminChatService()
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                AdminChatController(
                    adminTokenVerifier = AdminTokenVerifier(
                        AdminProperties(
                            token = "local-admin-token",
                            actor = "admin-local",
                            defaultLimit = 50,
                            maxLimit = 100,
                        ),
                    ),
                    adminChatService = service,
                    adminProperties = AdminProperties(
                        token = "local-admin-token",
                        actor = "admin-local",
                        defaultLimit = 50,
                        maxLimit = 100,
                    ),
                ),
            )
            .build()
    }

    @Test
    fun `관리자 history 조회는 token을 검증하고 limit을 양수 범위로 보정한다`() {
        mockMvc.get("/admin/chat-rooms/10/messages") {
            header("X-Admin-Token", "local-admin-token")
            param("from", "2026-06-14T00:00:00")
            param("to", "2026-06-15T00:00:00")
            param("limit", "-5")
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.messages[0].messageId") { value("msg-1") }
            }

        assertEquals("admin-local", service.historyActor)
        assertEquals(10L, service.historyRequest?.roomId)
        assertEquals(1, service.historyRequest?.limit)
    }

    @Test
    fun `관리자 history 조회는 offset date-time을 instant로 보존한다`() {
        mockMvc.get("/admin/chat-rooms/10/messages") {
            header("X-Admin-Token", "local-admin-token")
            param("from", "2026-06-14T09:00:00+09:00")
            param("to", "2026-06-14T10:00:00+09:00")
        }
            .andExpect {
                status { isOk() }
            }

        assertEquals(Instant.parse("2026-06-14T00:00:00Z"), service.historyRequest?.from)
        assertEquals(Instant.parse("2026-06-14T01:00:00Z"), service.historyRequest?.to)
    }

    @Test
    fun `관리자 search 조회는 잘못된 날짜 형식을 400으로 거부한다`() {
        mockMvc.get("/admin/messages/search") {
            header("X-Admin-Token", "local-admin-token")
            param("q", "hello")
            param("from", "not-a-date")
        }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `관리자 search 조회는 query와 필터를 service로 전달한다`() {
        mockMvc.get("/admin/messages/search") {
            header("X-Admin-Token", "local-admin-token")
            param("q", "hello")
            param("roomId", "10")
            param("senderId", "7")
            param("mode", "CONTAINS")
            param("limit", "500")
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.query") { value("hello") }
            }

        assertEquals("admin-local", service.searchActor)
        assertEquals("hello", service.searchRequest?.query)
        assertEquals(10L, service.searchRequest?.roomId)
        assertEquals(7L, service.searchRequest?.senderId)
        assertEquals(AdminMessageSearchMode.CONTAINS, service.searchRequest?.searchMode)
        assertEquals(100, service.searchRequest?.limit)
    }

    @Test
    fun `관리자 search 조회는 mode가 없으면 FTS를 기본값으로 사용한다`() {
        mockMvc.get("/admin/messages/search") {
            header("X-Admin-Token", "local-admin-token")
            param("q", "hello")
            param("roomId", "10")
        }
            .andExpect {
                status { isOk() }
            }

        assertEquals(AdminMessageSearchMode.FTS, service.searchRequest?.searchMode)
    }

    @Test
    fun `관리자 export 생성은 202로 job을 반환한다`() {
        mockMvc.post("/admin/exports/messages") {
            header("X-Admin-Token", "local-admin-token")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "roomId": 10,
                  "from": "2026-06-14T00:00:00",
                  "to": "2026-06-15T00:00:00",
                  "query": "hello"
                }
            """.trimIndent()
        }
            .andExpect {
                status { isAccepted() }
                jsonPath("$.jobId") { value("export-1") }
            }

        assertEquals("admin-local", service.exportActor)
        assertEquals(10L, service.exportRequest?.roomId)
        assertEquals("hello", service.exportRequest?.query)
    }

    @Test
    fun `관리자 export 생성은 roomId와 query가 모두 없으면 400으로 거부한다`() {
        mockMvc.post("/admin/exports/messages") {
            header("X-Admin-Token", "local-admin-token")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "from": "2026-06-14T00:00:00",
                  "to": "2026-06-15T00:00:00",
                  "query": "   "
                }
            """.trimIndent()
        }
            .andExpect {
                status { isBadRequest() }
            }

        assertEquals(null, service.exportRequest)
    }

    @Test
    fun `관리자 room status 조회는 방 상태 정보를 반환한다`() {
        mockMvc.get("/admin/rooms/10/status") {
            header("X-Admin-Token", "local-admin-token")
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.roomId") { value(10) }
                jsonPath("$.heatLevel") { value("NORMAL") }
            }

        assertEquals("admin-local", service.statusActor)
        assertEquals(10L, service.statusRoomId)
    }

    @Test
    fun `관리자 token이 없으면 401로 거부한다`() {
        mockMvc.get("/admin/messages/search") {
            param("q", "hello")
        }
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private class RecordingAdminChatService : AdminChatService {
        var historyActor: String? = null
        var historyRequest: AdminMessageHistoryRequest? = null
        var searchActor: String? = null
        var searchRequest: AdminMessageSearchRequest? = null
        var exportActor: String? = null
        var exportRequest: AdminExportMessagesRequest? = null
        var statusActor: String? = null
        var statusRoomId: Long? = null

        override fun getRoomMessages(
            actor: String,
            request: AdminMessageHistoryRequest,
        ): AdminMessagePageResponse {
            historyActor = actor
            historyRequest = request
            return AdminMessagePageResponse(
                messages = listOf(message()),
                nextCursor = null,
                hasNext = false,
                latencyMs = 3,
            )
        }

        override fun searchMessages(
            actor: String,
            request: AdminMessageSearchRequest,
        ): AdminMessageSearchResponse {
            searchActor = actor
            searchRequest = request
            return AdminMessageSearchResponse(
                query = request.query,
                messages = listOf(message()),
                nextCursor = null,
                hasNext = false,
                latencyMs = 5,
            )
        }

        override fun getRoomStatus(actor: String, roomId: Long): AdminRoomStatusDto {
            statusActor = actor
            statusRoomId = roomId
            return AdminRoomStatusDto(
                roomId = roomId,
                heatLevel = "NORMAL",
                liveFeedMaxMessages = 1000,
                liveFeedMaxAgeSeconds = 60,
                rateLimitPerSecond = null,
                slowModeSeconds = null,
                replicaLagMs = 0,
                searchP95LatencyMs = null,
            )
        }

        override fun createMessageExport(
            actor: String,
            request: AdminExportMessagesRequest,
        ): AdminExportJobDto {
            exportActor = actor
            exportRequest = request
            return AdminExportJobDto(
                jobId = "export-1",
                status = "PENDING",
                createdAt = LocalDateTime.parse("2026-06-14T00:00:00"),
            )
        }

        private fun message(): AdminMessageDto {
            return AdminMessageDto(
                messageId = "msg-1",
                clientMessageId = "client-1",
                roomId = 10L,
                roomSeq = 11L,
                writeShard = 0,
                senderId = 7L,
                senderUsername = "user7",
                senderDisplayName = "User 7",
                messageType = MessageType.TEXT,
                content = "hello",
                isDeleted = false,
                createdAt = Instant.parse("2026-06-14T00:00:00Z"),
            )
        }
    }
}
