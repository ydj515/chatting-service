package com.chat.api.controller

import com.chat.api.config.MessagePaginationProperties
import com.chat.api.security.FixedCurrentAuthenticationResolver
import com.chat.core.dto.ChatRoomDto
import com.chat.core.dto.ChatRoomMemberDto
import com.chat.core.dto.MessageDto
import com.chat.core.dto.MessageHistoryCursor
import com.chat.core.dto.MessageHistoryCursorCodec
import com.chat.core.dto.MessagePageRequest
import com.chat.core.dto.MessagePageResponse
import com.chat.core.message.command.SendMessageCommand
import com.chat.core.room.command.CreateChatRoomCommand
import com.chat.core.service.ChatService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class ChatControllerTest {
    private lateinit var mockMvc: MockMvc
    private lateinit var chatService: RecordingChatService

    @BeforeEach
    fun setUp() {
        chatService = RecordingChatService()

        mockMvc = MockMvcBuilders
            .standaloneSetup(
                ChatController(
                    chatService = chatService,
                    messagePaginationProperties = MessagePaginationProperties(
                        defaultLimit = 50,
                        maxLimit = 100,
                    ),
                ),
            )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(FixedCurrentAuthenticationResolver(userId = 42L))
            .build()
    }

    @Test
    fun `gap 조회 limit은 1 이상으로 보정한다`() {
        mockMvc.get("/chat-rooms/10/messages/gap") {
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

        override fun createChatRoom(request: CreateChatRoomCommand, createdBy: Long): ChatRoomDto = throw UnsupportedOperationException()

        override fun getChatRoom(roomId: Long, userId: Long): ChatRoomDto = throw UnsupportedOperationException()

        override fun getChatRooms(userId: Long, pageable: Pageable): Page<ChatRoomDto> = throw UnsupportedOperationException()

        override fun searchChatRooms(query: String, userId: Long): List<ChatRoomDto> = throw UnsupportedOperationException()

        override fun joinChatRoom(roomId: Long, userId: Long): Unit = throw UnsupportedOperationException()

        override fun leaveChatRoom(roomId: Long, userId: Long): Unit = throw UnsupportedOperationException()

        override fun getChatRoomMembers(roomId: Long, userId: Long): List<ChatRoomMemberDto> = throw UnsupportedOperationException()

        override fun sendMessage(request: SendMessageCommand, senderId: Long): MessageDto = throw UnsupportedOperationException()

        override fun getMessages(roomId: Long, userId: Long, pageable: Pageable): Page<MessageDto> = throw UnsupportedOperationException()
    }
}
