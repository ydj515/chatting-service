package com.chat.api.controller

import com.chat.api.config.MessagePaginationProperties
import com.chat.api.security.CurrentUserId
import com.chat.domain.dto.*
import com.chat.domain.service.ChatService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/chat-rooms")
class ChatController(
    private val chatService: ChatService,
    private val messagePaginationProperties: MessagePaginationProperties,
) {

    @PostMapping
    fun createChatRoom(
        @CurrentUserId userId: Long,
        @Valid @RequestBody request: CreateChatRoomRequest,
    ): ResponseEntity<ChatRoomDto> {
        val chatRoom = chatService.createChatRoom(request, userId)
        return ResponseEntity.ok(chatRoom)
    }

    @GetMapping("/{id}")
    fun getChatRoom(@PathVariable id: Long): ResponseEntity<ChatRoomDto> {
        val chatRoom = chatService.getChatRoom(id)
        return ResponseEntity.ok(chatRoom)
    }

    @GetMapping
    fun getChatRooms(
        @CurrentUserId authenticatedUserId: Long,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<ChatRoomDto>> {
        val chatRooms = chatService.getChatRooms(authenticatedUserId, pageable)
        return ResponseEntity.ok(chatRooms)
    }

    @PostMapping("/{id}/members")
    fun joinChatRoom(
        @CurrentUserId userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        chatService.joinChatRoom(id, userId)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{id}/members/me")
    fun leaveChatRoom(
        @CurrentUserId authenticatedUserId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        chatService.leaveChatRoom(id, authenticatedUserId)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/{id}/members")
    fun getChatRoomMembers(@PathVariable id: Long): ResponseEntity<List<ChatRoomMemberDto>> {
        val members = chatService.getChatRoomMembers(id)
        return ResponseEntity.ok(members)
    }

    // 메시지 조회만 제공 (히스토리 조회용)
    @GetMapping("/{id}/messages")
    fun getMessages(
        @CurrentUserId authenticatedUserId: Long,
        @PathVariable id: Long,
        @PageableDefault(size = 50) pageable: Pageable,
    ): ResponseEntity<Page<MessageDto>> {
        val messages = chatService.getMessages(id, authenticatedUserId, pageable)
        return ResponseEntity.ok(messages)
    }

    /**
     * 커서 기반 메시지 페이지네이션 (성능 최적화)
     */
    @GetMapping("/{id}/messages/cursor")
    fun getMessagesByCursor(
        @CurrentUserId authenticatedUserId: Long,
        @PathVariable id: Long,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false) cursorToken: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) direction: MessageDirection?,
    ): ResponseEntity<MessagePageResponse> {
        val request = MessagePageRequest(
            chatRoomId = id,
            cursor = cursor,
            cursorToken = parseCursorToken(cursorToken),
            limit = boundedMessageLimit(limit),
            direction = direction ?: messagePaginationProperties.defaultDirection,
        )
        val response = chatService.getMessagesByCursor(request, authenticatedUserId)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{id}/messages/gap")
    fun getMessagesGap(
        @CurrentUserId authenticatedUserId: Long,
        @PathVariable id: Long,
        @RequestParam afterSeq: Long,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<List<MessageDto>> {
        val boundedLimit = boundedMessageLimit(limit)
        val messages = chatService.getMessagesGap(
            roomId = id,
            userId = authenticatedUserId,
            afterSeq = afterSeq,
            limit = boundedLimit,
        )
        return ResponseEntity.ok(messages)
    }

    @GetMapping("/search")
    fun searchChatRooms(
        @CurrentUserId authenticatedUserId: Long,
        @RequestParam(required = false, defaultValue = "") q: String,
    ): ResponseEntity<List<ChatRoomDto>> {
        val chatRooms = chatService.searchChatRooms(q, authenticatedUserId)
        return ResponseEntity.ok(chatRooms)
    }

    private fun boundedMessageLimit(limit: Int?): Int {
        val maxLimit = messagePaginationProperties.maxLimit.coerceAtLeast(1)
        return (limit ?: messagePaginationProperties.defaultLimit).coerceIn(1, maxLimit)
    }

    private fun parseCursorToken(value: String?): String? {
        val cursorToken = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        MessageHistoryCursorCodec.decode(cursorToken)
        return cursorToken
    }
}
