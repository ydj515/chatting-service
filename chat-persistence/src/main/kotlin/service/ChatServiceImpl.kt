package com.chat.persistence.service

import com.chat.domain.dto.*
import com.chat.domain.model.*
import com.chat.domain.service.ChatService
import com.chat.persistence.repository.*
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamProducer
import com.chat.persistence.redis.RedisMessageBroker
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/*
    @CacheEvit
    @Cacheable
    @Caching
 */

@Service
@Transactional
class ChatServiceImpl(
    private val chatRoomRepository: ChatRoomRepository,
    private val messageRepository: MessageRepository,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val userRepository: UserRepository,
    private val redisMessageBroker: RedisMessageBroker,
    private val messageSequenceService: MessageSequenceService,
    private val messagePersistenceService: MessagePersistenceService,
    private val webSocketSessionManager: WebSocketSessionManager,
    private val messageStreamProducer: MessageStreamProducer,
) : ChatService {

    private val logger = LoggerFactory.getLogger(ChatServiceImpl::class.java)
    private val secureRandom = SecureRandom()


    @Cacheable(value = ["chatRooms"], key = "#chatRoom.id")
    private fun chatRoomToDto(chatRoom: ChatRoom): ChatRoomDto {
        val memberCount = chatRoomMemberRepository.countActiveMembersInRoom(chatRoom.id).toInt()
        val lastMessage = messageRepository.findLatestMessage(chatRoom.id)?.let { messageToDto(it) }

        return ChatRoomDto(
            id = chatRoom.id,
            name = chatRoom.name,
            description = chatRoom.description,
            type = chatRoom.type,
            imageUrl = chatRoom.imageUrl,
            isActive = chatRoom.isActive,
            maxMembers = chatRoom.maxMembers,
            memberCount = memberCount,
            createdBy = userToDto(chatRoom.createdBy),
            createdAt = chatRoom.createdAt,
            lastMessage = lastMessage
        )
    }

    private fun messageToDto(message: Message): MessageDto {
        val roomSeq = if (message.roomSeq > 0) message.roomSeq else message.sequenceNumber
        return MessageDto(
            id = message.id,
            messageId = message.messageId ?: legacyMessageId(message.id),
            clientMessageId = message.clientMessageId,
            chatRoomId = message.chatRoom.id,
            sender = userToDto(message.sender),
            type = message.type,
            content = message.content,
            isEdited = message.isEdited,
            isDeleted = message.isDeleted,
            createdAt = message.createdAt,
            editedAt = message.editedAt,
            sequenceNumber = message.sequenceNumber,
            roomSeq = roomSeq,
            streamShard = message.streamShard,
            writeShard = message.writeShard,
            fanoutShard = message.fanoutShard,
        )
    }

    private fun memberToDto(member: ChatRoomMember): ChatRoomMemberDto {
        return ChatRoomMemberDto(
            id = member.id,
            user = userToDto(member.user),
            role = member.role,
            isActive = member.isActive,
            lastReadMessageId = member.lastReadMessageId,
            joinedAt = member.joinedAt,
            leftAt = member.leftAt
        )
    }

    @Cacheable(value = ["users"], key = "#user.id")
    private fun userToDto(user: User): UserDto {
        return UserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            profileImageUrl = user.profileImageUrl,
            status = user.status,
            isActive = user.isActive,
            lastSeenAt = user.lastSeenAt,
            createdAt = user.createdAt
        )
    }

    @CacheEvict(value = ["chatRooms"], allEntries = true)
    override fun createChatRoom(
        request: CreateChatRoomRequest,
        createdBy: Long,
    ): ChatRoomDto {
        val creator = userRepository.findById(createdBy)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $createdBy") }

        val chatRoom = ChatRoom(
            name = request.name,
            description = request.description,
            type = request.type,
            imageUrl = request.imageUrl,
            maxMembers = request.maxMembers,
            createdBy = creator
        )

        val savedRoom = chatRoomRepository.save(chatRoom)

        val ownerMember = ChatRoomMember(
            chatRoom = savedRoom,
            user = creator,
            role = MemberRole.OWNER
        )
        chatRoomMemberRepository.save(ownerMember)

        publishMembershipChangedAfterCommit(creator.id, savedRoom.id, RedisMessageBroker.MembershipAction.JOIN)

        return chatRoomToDto(savedRoom)
    }

    @Cacheable(value = ["chatRooms"], key = "#roomId")
    override fun getChatRoom(roomId: Long): ChatRoomDto {
        val chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: $roomId") }
        return chatRoomToDto(chatRoom)
    }

    override fun getChatRooms(
        userId: Long,
        pageable: Pageable,
    ): Page<ChatRoomDto> {
        return chatRoomRepository.findUserChatRooms(userId, pageable)
            .map { chatRoomToDto(it) }
    }

    override fun searchChatRooms(
        query: String,
        userId: Long,
    ): List<ChatRoomDto> {
        val chatRooms = if (query.isBlank()) {
            chatRoomRepository.findByIsActiveTrueOrderByCreatedAtDesc()
        } else {
            chatRoomRepository.findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(query)
        }

        return chatRooms.map { chatRoomToDto(it) }
    }

    @Caching(evict = [
        CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
        CacheEvict(value = ["chatRooms"], key = "#roomId")
    ])
    override fun joinChatRoom(roomId: Long, userId: Long) {
        // 채팅방 확인
        val chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: $roomId") }

        // 사용자 확인
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $userId") }

        // 이미 참여중인지 확인
        if (chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw IllegalStateException("이미 참여한 채팅방입니다")
        }

//        val currentMemberCount = chatRoomMemberRepository.countActiveMembersInRoom(roomId)
//        if (currentMemberCount >= chatRoom.maxMembers) {
//            throw IllegalStateException("채팅방이 가득 찼습니다")
//        }

        val member = ChatRoomMember(
            chatRoom = chatRoom,
            user = user,
            role = MemberRole.MEMBER
        )
        chatRoomMemberRepository.save(member)

        publishMembershipChangedAfterCommit(userId, roomId, RedisMessageBroker.MembershipAction.JOIN)
    }

    @Caching(evict = [
        CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
        CacheEvict(value = ["chatRooms"], key = "#roomId")
    ])
    override fun leaveChatRoom(roomId: Long, userId: Long) {
        chatRoomMemberRepository.leaveChatRoom(roomId, userId)
        publishMembershipChangedAfterCommit(userId, roomId, RedisMessageBroker.MembershipAction.LEAVE)
    }

    @Cacheable(value = ["chatRoomMembers"], key = "#roomId")
    override fun getChatRoomMembers(roomId: Long): List<ChatRoomMemberDto> {
        return chatRoomMemberRepository.findByChatRoomIdAndIsActiveTrue(roomId)
            .map { memberToDto(it) }
    }


    override fun getMessages(
        roomId: Long,
        userId: Long,
        pageable: Pageable,
    ): Page<MessageDto> {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw IllegalArgumentException("채팅방 멤버가 아닙니다")
        }

        return messageRepository.findByChatRoomId(roomId, pageable)
            .map { messageToDto(it) }
    }

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
            throw IllegalArgumentException("채팅방 멤버가 아닙니다")
        }

        val pageable = PageRequest.of(0, request.limit)
        val cursor = request.cursor // effective roomSeq cursor

        val messages = when {
            cursor == null -> {
                // 커서가 없으면 최신 메시지부터
                messageRepository.findLatestMessages(request.chatRoomId, pageable)
            }
            request.direction == MessageDirection.BEFORE -> {
                // 커서 이전 메시지들 (과거 방향)
                messageRepository.findMessagesBefore(request.chatRoomId, cursor, pageable)
            }
            else -> {
                // 커서 이후 메시지들 (최신 방향)
                messageRepository.findMessagesAfter(request.chatRoomId, cursor, pageable)
                    .reversed() // 시간순 정렬로 변경
            }
        }

        val messageDtos = messages.map { messageToDto(it) }

        // 다음/이전 커서는 repository ordering key와 같은 effective roomSeq를 사용한다.
        val nextCursor = if (messageDtos.isNotEmpty()) messageDtos.last().roomSeq else null
        val prevCursor = if (messageDtos.isNotEmpty()) messageDtos.first().roomSeq else null

        // 추가 데이터 존재 여부 확인
        val hasNext = messages.size == request.limit
        val hasPrev = cursor != null

        return MessagePageResponse(
            messages = messageDtos,
            nextCursor = nextCursor,
            prevCursor = prevCursor,
            hasNext = hasNext,
            hasPrev = hasPrev
        )
    }

    override fun sendMessage(
        request: SendMessageRequest,
        senderId: Long,
    ): MessageDto {
        val requestedClientMessageId = normalizeClientMessageId(request.clientMessageId)
        val chatRoom = chatRoomRepository.findById(request.chatRoomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: ${request.chatRoomId}") }

        val sender = userRepository.findById(senderId)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $senderId") }

        chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, senderId)
            .orElseThrow { IllegalArgumentException("채팅방에 참여하지 않은 사용자입니다.") }

        if (requestedClientMessageId != null) {
            val existingMessage = messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                chatRoomId = request.chatRoomId,
                senderId = senderId,
                clientMessageId = requestedClientMessageId,
            ).orElse(null)
            if (existingMessage != null) {
                return messageToDto(existingMessage)
            }
        }

        val messageId = generateMessageId()
        val clientMessageId = requestedClientMessageId ?: "server:$messageId"
        val roomSeq = messageSequenceService.getNextSequence(request.chatRoomId)

        val message = Message(
            messageId = messageId,
            clientMessageId = clientMessageId,
            content = request.content,
            type = request.type ?: MessageType.TEXT,
            chatRoom = chatRoom,
            sender = sender,
            sequenceNumber = roomSeq,
            roomSeq = roomSeq,
            streamShard = streamShard(request.chatRoomId),
            writeShard = writeShard(messageId),
            fanoutShard = fanoutShard(request.chatRoomId),
        )

        messageStreamProducer.append(messageToStreamEnvelope(message))

        return messageToDto(message)
    }

    private fun messageToStreamEnvelope(message: Message): MessageStreamEnvelope {
        val roomSeq = if (message.roomSeq > 0) message.roomSeq else message.sequenceNumber
        return MessageStreamEnvelope(
            messageId = message.messageId ?: legacyMessageId(message.id),
            clientMessageId = message.clientMessageId,
            chatRoomId = message.chatRoom.id,
            senderId = message.sender.id,
            senderName = message.sender.displayName,
            messageType = message.type,
            content = message.content,
            sequenceNumber = message.sequenceNumber,
            roomSeq = roomSeq,
            streamShard = message.streamShard,
            writeShard = message.writeShard,
            fanoutShard = message.fanoutShard,
            createdAt = message.createdAt,
        )
    }

    private fun messageToChatMessage(message: Message): ChatMessage {
        val roomSeq = if (message.roomSeq > 0) message.roomSeq else message.sequenceNumber
        return ChatMessage(
            id = message.id,
            messageId = message.messageId ?: legacyMessageId(message.id),
            clientMessageId = message.clientMessageId,
            content = message.content ?: "",
            messageType = message.type,
            chatRoomId = message.chatRoom.id,
            senderId = message.sender.id,
            senderName = message.sender.displayName,
            sequenceNumber = message.sequenceNumber,
            roomSeq = roomSeq,
            streamShard = message.streamShard,
            writeShard = message.writeShard,
            fanoutShard = message.fanoutShard,
            timestamp = message.createdAt
        )
    }

    private fun normalizeClientMessageId(clientMessageId: String?): String? {
        return clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun generateMessageId(): String {
        val timestamp = Instant.now().toEpochMilli().toString(36).padStart(9, '0')
        val randomBytes = ByteArray(10)
        secureRandom.nextBytes(randomBytes)
        val randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        return "msg_${timestamp}_$randomPart"
    }

    private fun legacyMessageId(id: Long): String = "legacy:$id"

    private fun streamShard(roomId: Long): Int = shard(roomId.toString(), SHARD_COUNT)

    private fun writeShard(messageId: String): Int = shard(messageId, SHARD_COUNT)

    private fun fanoutShard(roomId: Long): Int = shard(roomId.toString(), SHARD_COUNT)

    private fun shard(value: String, shardCount: Int): Int {
        return Math.floorMod(value.hashCode(), shardCount.coerceAtLeast(1))
    }

    private fun publishMembershipChangedAfterCommit(
        userId: Long,
        roomId: Long,
        action: RedisMessageBroker.MembershipAction,
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishMembershipChanged(userId, roomId, action)
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                publishMembershipChanged(userId, roomId, action)
            }
        })
    }

    private fun publishMembershipChanged(
        userId: Long,
        roomId: Long,
        action: RedisMessageBroker.MembershipAction,
    ) {
        when (action) {
            RedisMessageBroker.MembershipAction.JOIN -> {
                if (webSocketSessionManager.isUserOnlineLocally(userId)) {
                    webSocketSessionManager.joinRoom(userId, roomId)
                }
            }
            RedisMessageBroker.MembershipAction.LEAVE -> webSocketSessionManager.leaveRoom(userId, roomId)
        }

        redisMessageBroker.publishMembershipChanged(
            userId = userId,
            roomId = roomId,
            action = action,
        )
    }

    private companion object {
        const val SHARD_COUNT = 1
    }
}
