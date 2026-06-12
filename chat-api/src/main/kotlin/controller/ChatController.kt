package com.chat.api.controller

import com.chat.api.config.MessagePaginationProperties
import com.chat.api.security.AuthenticatedUserResolver
import com.chat.domain.dto.*
import com.chat.domain.service.ChatService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/chat-rooms")
class ChatController(
    private val chatService: ChatService,
    private val messagePaginationProperties: MessagePaginationProperties,
    private val authenticatedUserResolver: AuthenticatedUserResolver,
) {

    @PostMapping
    fun createChatRoom(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @Valid @RequestBody request: CreateChatRoomRequest,
    ): ResponseEntity<ChatRoomDto> {
        val userId = authenticatedUserResolver.resolveRequired(authorization)
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
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<ChatRoomDto>> {
        val authenticatedUserId = authenticatedUserResolver.resolveRequired(authorization)
        val chatRooms = chatService.getChatRooms(authenticatedUserId, pageable)
        return ResponseEntity.ok(chatRooms)
    }

    @PostMapping("/{id}/members")
    fun joinChatRoom(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        val userId = authenticatedUserResolver.resolveRequired(authorization)
        chatService.joinChatRoom(id, userId)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{id}/members/me")
    fun leaveChatRoom(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        val authenticatedUserId = authenticatedUserResolver.resolveRequired(authorization)
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
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable id: Long,
        @PageableDefault(size = 50) pageable: Pageable,
    ): ResponseEntity<Page<MessageDto>> {
        val authenticatedUserId = authenticatedUserResolver.resolveRequired(authorization)
        val messages = chatService.getMessages(id, authenticatedUserId, pageable)
        return ResponseEntity.ok(messages)
    }

    /**
     * 커서 기반 메시지 페이지네이션 (성능 최적화)
     */
    @GetMapping("/{id}/messages/cursor")
    fun getMessagesByCursor(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable id: Long,
        // TODO: 향후 Long 타입을 Opaque cursor로 변경 검토
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) direction: MessageDirection?,
    ): ResponseEntity<MessagePageResponse> {
        val request = MessagePageRequest(
            chatRoomId = id,
            cursor = cursor,
            limit = (limit ?: messagePaginationProperties.defaultLimit)
                .coerceAtMost(messagePaginationProperties.maxLimit),
            direction = direction ?: messagePaginationProperties.defaultDirection,
        )
        val authenticatedUserId = authenticatedUserResolver.resolveRequired(authorization)
        val response = chatService.getMessagesByCursor(request, authenticatedUserId)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/search")
    fun searchChatRooms(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false, defaultValue = "") q: String,
    ): ResponseEntity<List<ChatRoomDto>> {
        val authenticatedUserId = authenticatedUserResolver.resolveRequired(authorization)
        val chatRooms = chatService.searchChatRooms(q, authenticatedUserId)
        return ResponseEntity.ok(chatRooms)
    }
}
