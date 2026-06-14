package com.chat.persistence.service

import com.chat.domain.dto.MessageDirection
import com.chat.domain.dto.MessagePageRequest
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.Message
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.config.MessageSequenceProperties
import com.chat.persistence.redis.MessageStreamProducer
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDateTime

@DataJpaTest
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
    fun `cursor pagination은 id가 아니라 roomSeq ordering key 기준으로 다음 페이지를 조회한다`() {
        val sender = userRepository.save(user("sender"))
        val chatRoom = chatRoomRepository.save(chatRoom("room", sender))
        chatRoomMemberRepository.save(ChatRoomMember(chatRoom = chatRoom, user = sender))
        val highRoomSeqMessage = messageRepository.save(
            message(
                chatRoom = chatRoom,
                sender = sender,
                messageId = "msg-high",
                clientMessageId = "client-high",
                roomSeq = 1001L,
                createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            ),
        )
        val lowRoomSeqMessage = messageRepository.save(
            message(
                chatRoom = chatRoom,
                sender = sender,
                messageId = "msg-low",
                clientMessageId = "client-low",
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
                limit = 1,
                direction = MessageDirection.BEFORE,
            ),
            userId = sender.id,
        )

        assertEquals(listOf(highRoomSeqMessage.id), firstPage.messages.map { it.id })
        assertEquals(1001L, firstPage.nextCursor)
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
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisMessageBroker = redisMessageBroker,
            chatRoomMemberRepository = chatRoomMemberRepository,
            redisProperties = redisProperties,
            gatewayProperties = ChatWebSocketGatewayProperties(),
            outboundExecutor = Runnable::run,
        )

        return ChatServiceImpl(
            chatRoomRepository = chatRoomRepository,
            messageRepository = messageRepository,
            messageReadPort = JpaMessageReadAdapter(messageRepository),
            chatRoomMemberRepository = chatRoomMemberRepository,
            userRepository = userRepository,
            redisMessageBroker = redisMessageBroker,
            messageSequenceService = MessageSequenceService(
                redisTemplate = redisTemplate,
                redisProperties = redisProperties,
                sequenceProperties = MessageSequenceProperties(),
            ),
            messagePersistenceService = MessagePersistenceService(messageRepository),
            webSocketSessionManager = webSocketSessionManager,
            messageStreamProducer = mock(MessageStreamProducer::class.java),
        )
    }

    private fun user(username: String): User {
        return User(
            username = username,
            password = "password",
            displayName = username,
        )
    }

    private fun chatRoom(name: String, createdBy: User): ChatRoom {
        return ChatRoom(
            name = name,
            createdBy = createdBy,
        )
    }

    private fun message(
        chatRoom: ChatRoom,
        sender: User,
        messageId: String,
        clientMessageId: String,
        roomSeq: Long,
        createdAt: LocalDateTime,
    ): Message {
        return Message(
            messageId = messageId,
            clientMessageId = clientMessageId,
            chatRoom = chatRoom,
            sender = sender,
            type = MessageType.TEXT,
            content = messageId,
            sequenceNumber = roomSeq,
            roomSeq = roomSeq,
            createdAt = createdAt,
        )
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.chat.domain.model")
    @EnableJpaRepositories("com.chat.persistence.repository")
    open class JpaTestConfig
}
