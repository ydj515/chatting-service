package com.chat.persistence.service

import com.chat.core.dto.*
import com.chat.core.message.port.MessageReadPort
import com.chat.core.service.ChatService
import com.chat.domain.exception.ForbiddenOperationException
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.exception.ResourceNotFoundException
import com.chat.domain.model.*
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.*
import org.springframework.cache.annotation.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

@Service
class ChatServiceImpl(
    private val chatRoomRepository: ChatRoomRepository,
    private val messageReadPort: MessageReadPort,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val userRepository: UserRepository,
    private val messageSendingService: MessageSendingService,
    private val membershipEventPublisher: MembershipEventPublisher,
) : ChatService {
    // Sensitive room reads remain uncached so membership is checked on every request.
    private fun chatRoomToDto(
        chatRoom: ChatRoom,
        memberCount: Int = chatRoomMemberRepository.countActiveMembersInRoom(chatRoom.id).toInt(),
        lastMessage: MessageDto? = messageReadPort.findLatestMessage(chatRoom.id),
    ): ChatRoomDto = ChatRoomDto(
        id = chatRoom.id,
        name = chatRoom.name,
        description = chatRoom.description,
        type = chatRoom.type,
        imageUrl = chatRoom.imageUrl,
        isActive = chatRoom.isActive,
        maxMembers = chatRoom.maxMembers,
        memberCount = memberCount,
        createdBy = chatRoom.createdBy.toUserDto(),
        createdAt = chatRoom.createdAt,
        lastMessage = lastMessage,
    )

    private fun memberToDto(member: ChatRoomMember): ChatRoomMemberDto =
        ChatRoomMemberDto(
            id = member.id,
            user = member.user.toUserDto(),
            role = member.role,
            isActive = member.isActive,
            lastReadMessageId = member.lastReadMessageId,
            joinedAt = member.joinedAt,
            leftAt = member.leftAt,
        )

    @CacheEvict(value = ["chatRooms"], allEntries = true)
    @Transactional
    override fun createChatRoom(
        request: CreateChatRoomRequest,
        createdBy: Long,
    ): ChatRoomDto {
        require(request.maxMembers >= 1) { "maxMembers must be positive" }
        val creator = userRepository.findById(createdBy)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다: $createdBy") }

        val chatRoom = ChatRoom(
            name = request.name,
            description = request.description,
            type = request.type,
            imageUrl = request.imageUrl,
            maxMembers = request.maxMembers,
            createdBy = creator,
        )

        val savedRoom = chatRoomRepository.save(chatRoom)

        val ownerMember = ChatRoomMember(
            chatRoom = savedRoom,
            user = creator,
            role = MemberRole.OWNER,
        )
        chatRoomMemberRepository.save(ownerMember)

        membershipEventPublisher.publishAfterCommit(creator.id, savedRoom.id, RedisMessageBroker.MembershipAction.JOIN)

        return chatRoomToDto(savedRoom)
    }

    @Transactional(readOnly = true)
    override fun getChatRoom(roomId: Long, userId: Long): ChatRoomDto {
        requireMembership(roomId, userId)
        val chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow { ResourceNotFoundException("채팅방을 찾을 수 없습니다: $roomId") }
        return chatRoomToDto(chatRoom)
    }

    @Transactional(readOnly = true)
    override fun getChatRooms(
        userId: Long,
        pageable: Pageable,
    ): Page<ChatRoomDto> {
        val rooms = chatRoomRepository.findUserChatRooms(userId, pageable)
        val dtos = chatRoomsToDtos(rooms.content).associateBy { it.id }
        return rooms.map { dtos.getValue(it.id) }
    }

    private fun chatRoomsToDtos(rooms: List<ChatRoom>, includeLastMessage: Boolean = true): List<ChatRoomDto> {
        if (rooms.isEmpty()) return emptyList()
        val ids = rooms.map { it.id }
        val counts = chatRoomMemberRepository.countActiveMembersByRooms(ids).associate { it.roomId to it.memberCount.toInt() }
        val messages = if (includeLastMessage) messageReadPort.findLatestMessagesByRooms(ids) else emptyMap()
        return rooms.map { chatRoomToDto(it, counts[it.id] ?: 0, messages[it.id]) }
    }

    @Transactional(readOnly = true)
    override fun searchChatRooms(
        query: String,
        userId: Long,
    ): List<ChatRoomDto> {
        val chatRooms = if (query.isBlank()) {
            chatRoomRepository.findByIsActiveTrueOrderByCreatedAtDesc()
        } else {
            chatRoomRepository.findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(query)
        }

        return chatRoomsToDtos(chatRooms, includeLastMessage = false)
    }

    @Caching(
        evict = [
            CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
            CacheEvict(value = ["chatRooms"], key = "#roomId"),
        ],
    )
    @Transactional
    override fun joinChatRoom(roomId: Long, userId: Long) {
        // 채팅방 확인
        val chatRoom = chatRoomRepository.findByIdForMembershipUpdate(roomId)
            ?: throw ResourceNotFoundException("채팅방을 찾을 수 없습니다: $roomId")

        // 사용자 확인
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다: $userId") }

        // 이미 참여중인지 확인
        if (chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw ResourceConflictException("이미 참여한 채팅방입니다")
        }

        if (chatRoomMemberRepository.countActiveMembersInRoom(roomId) >= chatRoom.maxMembers) {
            throw ResourceConflictException("Chat room is full")
        }

        if (chatRoomMemberRepository.reactivateMembership(roomId, userId) == 0) {
            val member = ChatRoomMember(
                chatRoom = chatRoom,
                user = user,
                role = MemberRole.MEMBER,
            )
            chatRoomMemberRepository.save(member)
        }

        membershipEventPublisher.publishAfterCommit(userId, roomId, RedisMessageBroker.MembershipAction.JOIN)
    }

    @Caching(
        evict = [
            CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
            CacheEvict(value = ["chatRooms"], key = "#roomId"),
        ],
    )
    @Transactional
    override fun leaveChatRoom(roomId: Long, userId: Long) {
        chatRoomMemberRepository.leaveChatRoom(roomId, userId)
        membershipEventPublisher.publishAfterCommit(userId, roomId, RedisMessageBroker.MembershipAction.LEAVE)
    }

    @Transactional(readOnly = true)
    override fun getChatRoomMembers(roomId: Long, userId: Long): List<ChatRoomMemberDto> {
        requireMembership(roomId, userId)
        return chatRoomMemberRepository.findByChatRoomIdAndIsActiveTrue(roomId).map { memberToDto(it) }
    }

    private fun requireMembership(roomId: Long, userId: Long) {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw ForbiddenOperationException("채팅방 멤버가 아닙니다")
        }
    }

    @Transactional(readOnly = true)
    override fun getMessages(
        roomId: Long,
        userId: Long,
        pageable: Pageable,
    ): Page<MessageDto> {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw ForbiddenOperationException("채팅방 멤버가 아닙니다")
        }

        return messageReadPort.findPageByRoom(roomId, pageable)
    }

    @Transactional(readOnly = true)
    override fun getMessagesByCursor(
        request: MessagePageRequest,
        userId: Long,
    ): MessagePageResponse {
        /*
            SELECT *
            FROM chat_room_member
            WHERE chat_room_id = :chatRoomId AND is_active = true
            ORDER BY id
            LIMIT 10 OFFSET 10;

            SELECT *
            FROM chat_room_member
            WHERE chat_room_id = :chatRoomId AND is_active = true AND id > :cursor
            ORDER BY id ASC
            LIMIT 10;
         */

        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, userId)) {
            throw ForbiddenOperationException("채팅방 멤버가 아닙니다")
        }

        val cursor = request.effectiveCursor() // effective roomSeq cursor

        val messages = when {
            cursor == null -> {
                // 커서가 없으면 최신 메시지부터
                messageReadPort.findLatestMessages(request.chatRoomId, request.limit)
            }
            request.direction == MessageDirection.BEFORE -> {
                // 커서 이전 메시지들 (과거 방향)
                messageReadPort.findMessagesBefore(request.chatRoomId, cursor, request.limit)
            }
            else -> {
                // 커서 이후 메시지들 (최신 방향)
                messageReadPort.findMessagesAfter(request.chatRoomId, cursor, request.limit)
                    .reversed() // 시간순 정렬로 변경
            }
        }

        // 다음/이전 커서는 repository ordering key와 같은 effective roomSeq를 사용한다.
        val nextCursor = if (messages.isNotEmpty()) messages.last().roomSeq else null
        val prevCursor = if (messages.isNotEmpty()) messages.first().roomSeq else null
        val nextCursorToken = messages.lastOrNull()?.toHistoryCursorToken()
        val prevCursorToken = messages.firstOrNull()?.toHistoryCursorToken()

        // 추가 데이터 존재 여부 확인
        val hasNext = messages.size == request.limit
        val hasPrev = cursor != null

        return MessagePageResponse(
            messages = messages,
            nextCursor = nextCursor,
            nextCursorToken = nextCursorToken,
            prevCursor = prevCursor,
            prevCursorToken = prevCursorToken,
            hasNext = hasNext,
            hasPrev = hasPrev,
        )
    }

    private fun MessagePageRequest.effectiveCursor(): Long? {
        val tokenCursor = MessageHistoryCursorCodec.decode(cursorToken)
        return tokenCursor?.roomSeq ?: cursor
    }

    private fun MessageDto.toHistoryCursorToken(): String =
        MessageHistoryCursorCodec.encode(
            MessageHistoryCursor(
                createdAt = createdAt.atOffset(ZoneOffset.UTC).toInstant(),
                roomSeq = roomSeq,
                messageId = messageId,
            ),
        )

    @Transactional(readOnly = true)
    override fun getMessagesGap(
        roomId: Long,
        userId: Long,
        afterSeq: Long,
        limit: Int,
    ): List<MessageDto> {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw ForbiddenOperationException("채팅방 멤버가 아닙니다")
        }

        return messageReadPort.findGapMessages(roomId, afterSeq, limit)
    }

    override fun sendMessage(request: SendMessageRequest, senderId: Long): MessageDto = messageSendingService.sendMessage(request, senderId)
}
