package com.chat.core.dto

import com.chat.domain.model.ChatRoomType
import com.chat.domain.model.MemberRole
import com.chat.domain.model.MessageType
import java.time.LocalDateTime

data class ChatRoomDto(
    val id: Long,
    val name: String,
    val description: String?,
    val type: ChatRoomType,
    val imageUrl: String?,
    val isActive: Boolean,
    val maxMembers: Int,
    val memberCount: Int,
    val createdBy: UserDto,
    val createdAt: LocalDateTime,
    val lastMessage: MessageDto?,
)

data class MessageDto(
    val id: Long,
    val messageId: String,
    val clientMessageId: String?,
    val chatRoomId: Long,
    val sender: UserDto,
    val type: MessageType,
    val content: String?,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime,
    val editedAt: LocalDateTime?,
    val sequenceNumber: Long = 0, // 기존 호환 필드
    val roomSeq: Long = 0,
    val streamShard: Int = 0,
    val writeShard: Int = 0,
    val fanoutShard: Int = 0,
)

// 커서 기반 페이지네이션을 위한 DTO
data class MessagePageRequest(
    val chatRoomId: Long,
    val cursor: Long? = null, // 마지막 메시지의 실제 정렬 roomSeq (없으면 최신부터)
    val cursorToken: String? = null, // opaque cursor. 있으면 numeric cursor보다 우선한다.
    val limit: Int = 50,
    val direction: MessageDirection = MessageDirection.BEFORE, // 커서 기준 이전/이후
)

data class MessagePageResponse(
    val messages: List<MessageDto>,
    val nextCursor: Long?, // legacy numeric cursor
    val nextCursorToken: String?, // 다음 페이지를 위한 opaque cursor
    val prevCursor: Long?, // legacy numeric cursor
    val prevCursorToken: String?, // 이전 페이지를 위한 opaque cursor
    val hasNext: Boolean,
    val hasPrev: Boolean,
)

enum class MessageDirection {
    BEFORE, // 커서 이전 메시지들 (과거)
    AFTER, // 커서 이후 메시지들 (최신)
}

data class ChatRoomMemberDto(
    val id: Long,
    val user: UserDto,
    val role: MemberRole,
    val isActive: Boolean,
    val lastReadMessageId: Long?,
    val joinedAt: LocalDateTime,
    val leftAt: LocalDateTime?,
)
