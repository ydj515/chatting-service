package com.chat.api.controller

import com.chat.api.config.MessagePaginationProperties
import com.chat.api.security.AuthenticatedUserResolver
import com.chat.domain.dto.AuthenticatedSession
import com.chat.domain.dto.ChatRoomDto
import com.chat.domain.dto.ChatRoomMemberDto
import com.chat.domain.dto.CreateChatRoomRequest
import com.chat.domain.dto.MessageHistoryCursor
import com.chat.domain.dto.MessageHistoryCursorCodec
import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.MessagePageRequest
import com.chat.domain.dto.MessagePageResponse
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.service.ChatService
import com.chat.domain.service.SessionTokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.time.LocalDateTime

class ChatControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var chatService: RecordingChatService
    private lateinit var sessionTokenService: SessionTokenService

    @BeforeEach
    fun setUp() {
        chatService = RecordingChatService()
        sessionTokenService = mock(SessionTokenService::class.java)
        `when`(sessionTokenService.authenticate("session-token")).thenReturn(
            AuthenticatedSession(
                userId = 42L,
                expiresAt = LocalDateTime.parse("2026-06-13T00:30:00"),
            ),
        )

        mockMvc = MockMvcBuilders
            .standaloneSetup(
                ChatController(
                    chatService = chatService,
                    messagePaginationProperties = MessagePaginationProperties(
                        defaultLimit = 50,
                        maxLimit = 100,
                    ),
                    authenticatedUserResolver = AuthenticatedUserResolver(sessionTokenService),
                ),
            )
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `gap 조회 limit은 1 이상으로 보정한다`() {
        mockMvc.get("/chat-rooms/10/messages/gap") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            param("afterSeq", "7")
            param("limit", "-5")
        }
            .andExpect {
                status { isOk() }
            }

        assertEquals(1, chatService.capturedGapLimit)
    }

    @Test
    fun `cursor 조회 limit은 1 이상으로 보정한다`() {
        mockMvc.get("/chat-rooms/10/messages/cursor") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            param("limit", "-5")
        }
            .andExpect {
                status { isOk() }
            }

        assertEquals(1, chatService.capturedPageRequest?.limit)
    }

    @Test
    fun `cursor 조회는 opaque cursorToken을 service request로 전달한다`() {
        val cursorToken = MessageHistoryCursorCodec.encode(
            MessageHistoryCursor(
                createdAt = Instant.parse("2026-06-14T00:00:01Z"),
                roomSeq = 1001L,
                messageId = "msg-1001",
            ),
        )

        mockMvc.get("/chat-rooms/10/messages/cursor") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            param("cursor", "999")
            param("cursorToken", cursorToken)
            param("limit", "50")
        }
            .andExpect {
                status { isOk() }
            }

        assertEquals(999L, chatService.capturedPageRequest?.cursor)
        assertEquals(cursorToken, chatService.capturedPageRequest?.cursorToken)
    }

    @Test
    fun `cursor 조회는 잘못된 cursorToken을 400으로 거부한다`() {
        mockMvc.get("/chat-rooms/10/messages/cursor") {
            header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            param("cursorToken", "not-a-valid-cursor")
        }
            .andExpect {
                status { isBadRequest() }
            }

        assertEquals(null, chatService.capturedPageRequest)
    }

    private class RecordingChatService : ChatService {
        var capturedGapLimit: Int? = null
        var capturedPageRequest: MessagePageRequest? = null

        override fun getMessagesByCursor(request: MessagePageRequest, userId: Long): MessagePageResponse {
            capturedPageRequest = request
            return MessagePageResponse(
                messages = emptyList(),
                nextCursor = null,
                nextCursorToken = null,
                prevCursor = null,
                prevCursorToken = null,
                hasNext = false,
                hasPrev = false,
            )
        }

        override fun getMessagesGap(roomId: Long, userId: Long, afterSeq: Long, limit: Int): List<MessageDto> {
            capturedGapLimit = limit
            return emptyList()
        }

        override fun createChatRoom(request: CreateChatRoomRequest, createdBy: Long): ChatRoomDto {
            throw UnsupportedOperationException()
        }

        override fun getChatRoom(roomId: Long): ChatRoomDto {
            throw UnsupportedOperationException()
        }

        override fun getChatRooms(userId: Long, pageable: Pageable): Page<ChatRoomDto> {
            throw UnsupportedOperationException()
        }

        override fun searchChatRooms(query: String, userId: Long): List<ChatRoomDto> {
            throw UnsupportedOperationException()
        }

        override fun joinChatRoom(roomId: Long, userId: Long) {
            throw UnsupportedOperationException()
        }

        override fun leaveChatRoom(roomId: Long, userId: Long) {
            throw UnsupportedOperationException()
        }

        override fun getChatRoomMembers(roomId: Long): List<ChatRoomMemberDto> {
            throw UnsupportedOperationException()
        }

        override fun sendMessage(request: SendMessageRequest, senderId: Long): MessageDto {
            throw UnsupportedOperationException()
        }

        override fun getMessages(roomId: Long, userId: Long, pageable: Pageable): Page<MessageDto> {
            throw UnsupportedOperationException()
        }
    }
}
