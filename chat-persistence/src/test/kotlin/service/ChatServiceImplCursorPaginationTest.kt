package com.chat.persistence.service

import com.chat.core.dto.MessageDirection
import com.chat.core.dto.MessageDto
import com.chat.core.dto.MessageHistoryCursor
import com.chat.core.dto.MessageHistoryCursorCodec
import com.chat.core.dto.MessagePageRequest
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.MemberRole
import com.chat.domain.model.Message
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.redis.MessageStreamProducer
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.hibernate.Hibernate
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.context.ContextConfiguration
import java.time.Instant
import java.time.LocalDateTime

@DataJpaTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@ContextConfiguration(classes = [ChatServiceImplCursorPaginationTest.JpaTestConfig::class])
class ChatServiceImplCursorPaginationTest {
    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    private lateinit var messageRepository: MessageRepository

    @Autowired
    private lateinit var chatRoomMemberRepository: ChatRoomMemberRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `rejoining does not restore a previous moderator role`() {
        val creator = userRepository.save(user("former-moderator"))
        val room = chatRoomRepository.save(chatRoom("moderated-room", creator))
        chatRoomMemberRepository.save(ChatRoomMember(chatRoom = room, user = creator, role = MemberRole.ADMIN, isActive = false, leftAt = LocalDateTime.now()))
        entityManager.flush()
        entityManager.clear()
        chatService().joinChatRoom(room.id, creator.id)
        entityManager.flush()
        entityManager.clear()
        val member = requireNotNull(chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(room.id, creator.id))
        assertEquals(MemberRole.MEMBER, member.role)
    }

    @Test
    fun `batch latest messages use effective sequence and omit empty rooms`() {
        val creator = userRepository.save(user("latest-user"))
        val room = chatRoomRepository.save(chatRoom("latest-room", creator))
        val emptyRoom = chatRoomRepository.save(chatRoom("empty-room", creator))
        messageRepository.save(Message(chatRoom = room, sender = creator, content = "legacy", sequenceNumber = 10L, roomSeq = 0L))
        messageRepository.save(Message(chatRoom = room, sender = creator, content = "canonical", sequenceNumber = 100L, roomSeq = 2L))
        messageRepository.save(Message(chatRoom = room, sender = creator, content = "deleted", sequenceNumber = 20L, roomSeq = 20L, isDeleted = true))
        entityManager.flush()
        entityManager.clear()
        val adapter = JpaMessageReadAdapter(messageRepository)
        val result = adapter.findLatestMessagesByRooms(listOf(room.id, emptyRoom.id))
        assertEquals(setOf(room.id), result.keys)
        assertEquals("legacy", result.getValue(room.id).content)
        assertEquals(emptyMap<Long, MessageDto>(), adapter.findLatestMessagesByRooms(emptyList()))
    }

    @Test
    fun `JPA associations remain lazy until accessed`() {
        val creator = userRepository.save(user("lazy-user"))
        val room = chatRoomRepository.save(chatRoom("lazy-room", creator))
        entityManager.flush()
        entityManager.clear()
        val loaded = entityManager.find(ChatRoom::class.java, room.id)
        assertFalse(Hibernate.isInitialized(loaded.createdBy))
        assertEquals("lazy-user", loaded.createdBy.displayName)
        assertTrue(Hibernate.isInitialized(loaded.createdBy))
    }

    @Test
    fun `join leave and rejoin reuse the unique membership row`() {
        val creator = userRepository.save(user("rejoin-user"))
        val room = chatRoomRepository.save(chatRoom("rejoin-room", creator))
        val service = chatService()
        service.joinChatRoom(room.id, creator.id)
        entityManager.flush()
        val original = requireNotNull(chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(room.id, creator.id))
        service.leaveChatRoom(room.id, creator.id)
        entityManager.flush()
        entityManager.clear()
        service.joinChatRoom(room.id, creator.id)
        entityManager.flush()
        entityManager.clear()
        val restored = requireNotNull(chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(room.id, creator.id))
        assertEquals(original.id, restored.id)
        assertEquals(null, restored.leftAt)
        assertEquals(1L, chatRoomMemberRepository.countActiveMembersInRoom(room.id))
        assertThrows(ResourceConflictException::class.java) {
            service.joinChatRoom(room.id, creator.id)
        }
    }

    @Test
    fun `room list query count stays constant as page size increases`() {
        val creator = userRepository.save(user("batch-user"))
        repeat(12) { index ->
            val room = chatRoomRepository.save(chatRoom("batch-$index", creator))
            chatRoomMemberRepository.save(ChatRoomMember(chatRoom = room, user = creator))
            messageRepository.save(message(room, creator, "batch-msg-$index", 1L, LocalDateTime.now()))
        }
        entityManager.flush()
        val statistics = entityManager.entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val service = chatService()

        fun queryCount(size: Int): Long {
            entityManager.clear()
            statistics.clear()
            val result = service.getChatRooms(creator.id, PageRequest.of(0, size))
            assertEquals(size, result.content.size)
            result.forEach {
                assertEquals(1, it.memberCount)
                assertNotNull(it.lastMessage)
            }
            return statistics.prepareStatementCount
        }
        val smallPageCount = queryCount(2)
        assertEquals(smallPageCount, queryCount(10))
        assertTrue(smallPageCount <= 4, "Expected at most four batch queries")
        entityManager.clear()
        statistics.clear()
        assertEquals(12, service.searchChatRooms("batch-", creator.id).size)
        assertTrue(statistics.prepareStatementCount <= 3)
    }

    @Test
    fun `cursor pagination은 id가 아니라 roomSeq ordering key 기준으로 다음 페이지를 조회한다`() {
        val sender = userRepository.save(user("sender"))
        val chatRoom = chatRoomRepository.save(chatRoom("room", sender))
        chatRoomMemberRepository.save(ChatRoomMember(chatRoom = chatRoom, user = sender))
        val highRoomSeqMessage = messageRepository.save(
            message(
                chatRoom = chatRoom,
                sender = sender,
                messageId = "msg-high",
                roomSeq = 1001L,
                createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            ),
        )
        val lowRoomSeqMessage = messageRepository.save(
            message(
                chatRoom = chatRoom,
                sender = sender,
                messageId = "msg-low",
                roomSeq = 2L,
                createdAt = LocalDateTime.parse("2026-06-12T12:00:01"),
            ),
        )
        messageRepository.flush()
        entityManager.clear()

        val chatService = chatService()

        val firstPage = chatService.getMessagesByCursor(
            MessagePageRequest(
                chatRoomId = chatRoom.id,
                cursor = null,
                limit = 1,
                direction = MessageDirection.BEFORE,
            ),
            userId = sender.id,
        )
        val secondPage = chatService.getMessagesByCursor(
            MessagePageRequest(
                chatRoomId = chatRoom.id,
                cursor = firstPage.nextCursor,
                cursorToken = firstPage.nextCursorToken,
                limit = 1,
                direction = MessageDirection.BEFORE,
            ),
            userId = sender.id,
        )

        assertEquals(listOf(highRoomSeqMessage.id), firstPage.messages.map { it.id })
        assertEquals(1001L, firstPage.nextCursor)
        assertEquals(
            MessageHistoryCursor(
                createdAt = Instant.parse("2026-06-12T12:00:00Z"),
                roomSeq = 1001L,
                messageId = "msg-high",
            ),
            MessageHistoryCursorCodec.decode(firstPage.nextCursorToken),
        )
        assertEquals(listOf(lowRoomSeqMessage.id), secondPage.messages.map { it.id })
        assertEquals(2L, secondPage.nextCursor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun chatService(): ChatServiceImpl {
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
        val redisProperties = ChatRedisProperties(
            broker = ChatRedisProperties.Broker(serverId = "test-server"),
        )
        val redisMessageBroker = RedisMessageBroker(
            redisTemplate = redisTemplate,
            messageListenerContainer = mock(RedisMessageListenerContainer::class.java),
            objectMapper = objectMapper,
            redisProperties = redisProperties,
        )
        val webSocketSessionManager = WebSocketSessionManager(
            objectMapper = objectMapper,
            redisMessageBroker = redisMessageBroker,
            chatRoomMemberRepository = chatRoomMemberRepository,
            roomSubscriptions = WebSocketRoomSubscriptions(
                redisTemplate = redisTemplate,
                redisProperties = redisProperties,
                redisMessageBroker = redisMessageBroker,
            ),
            transport = WebSocketSessionTransport(
                authorization = org.mockito.Mockito.mock(WebSocketSessionAuthorization::class.java),
                gatewayProperties = ChatWebSocketGatewayProperties(),
                outboundExecutor = Runnable::run,
            ),
        )

        return ChatServiceImpl(
            chatRoomRepository = chatRoomRepository,
            messageReadPort = JpaMessageReadAdapter(messageRepository),
            chatRoomMemberRepository = chatRoomMemberRepository,
            userRepository = userRepository,
            messageSendingService = MessageSendingService(
                chatRoomRepository = chatRoomRepository,
                userRepository = userRepository,
                chatRoomMemberRepository = chatRoomMemberRepository,
                messageReadPort = JpaMessageReadAdapter(messageRepository),
                messageSendPolicy = MessageSendPolicy(
                    userSanctionPolicyService = UserSanctionPolicyService.Noop,
                    messageModerationPolicyService = MessageModerationPolicyService.Noop,
                    messageAdmissionPolicyService = MessageAdmissionPolicyService.Noop,
                ),
                messageStreamPublisher = MessageStreamPublisher(
                    messageSequenceService = MessageSequenceService(
                        redisTemplate = redisTemplate,
                        redisProperties = redisProperties,
                    ),
                    roomStorageConfigReader = TestRoomStorageConfigReader,
                    messageStreamProducer = mock(MessageStreamProducer::class.java),
                    roomTrafficStatsService = RoomTrafficStatsService.Noop,
                ),
            ),
            membershipEventPublisher = MembershipEventPublisher(
                redisMessageBroker = redisMessageBroker,
                webSocketSessionManager = webSocketSessionManager,
            ),
        )
    }

    private object TestRoomStorageConfigReader : RoomStorageConfigReader {
        override fun currentShardCount(roomId: Long): Int = 1

        override fun shardConfig(roomId: Long): RoomShardConfig = RoomShardConfig()
    }

    private fun user(username: String): User =
        User(
            username = username,
            password = "password",
            displayName = username,
        )

    private fun chatRoom(name: String, createdBy: User): ChatRoom =
        ChatRoom(
            name = name,
            createdBy = createdBy,
        )

    private fun message(
        chatRoom: ChatRoom,
        sender: User,
        messageId: String,
        roomSeq: Long,
        createdAt: LocalDateTime,
    ): Message =
        Message(
            messageId = messageId,
            clientMessageId = "client-$messageId",
            chatRoom = chatRoom,
            sender = sender,
            type = MessageType.TEXT,
            content = messageId,
            sequenceNumber = roomSeq,
            roomSeq = roomSeq,
            createdAt = createdAt,
        )

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.chat.domain.model")
    @EnableJpaRepositories("com.chat.persistence.repository")
    open class JpaTestConfig
}
