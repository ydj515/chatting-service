package com.chat.persistence.service

import com.chat.domain.dto.MessageDirection
import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.MessageHistoryCursor
import com.chat.domain.dto.MessageHistoryCursorCodec
import com.chat.domain.dto.MessagePageRequest
import com.chat.domain.dto.UserDto
import com.chat.domain.model.MessageType
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
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Instant
import java.time.LocalDateTime

class ChatServiceImplCanonicalHistoryTest {

    @Test
    fun `getMessages는 canonical read port의 page 결과를 반환한다`() {
        val readPort = FakeMessageReadPort(
            page = PageImpl(listOf(messageDto(roomSeq = 10L))),
        )
        val fixture = chatServiceFixture(readPort)
        `when`(fixture.chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L))
            .thenReturn(true)

        val messages = fixture.chatService.getMessages(
            roomId = 10L,
            userId = 7L,
            pageable = PageRequest.of(0, 50),
        )

        assertEquals(listOf(10L), messages.content.map { it.roomSeq })
        assertEquals(listOf("page:10:0:50"), readPort.calls)
    }

    @Test
    fun `getMessagesByCursor는 cursor direction에 맞는 canonical read port 결과를 사용한다`() {
        val readPort = FakeMessageReadPort(
            before = listOf(messageDto(roomSeq = 9L)),
        )
        val fixture = chatServiceFixture(readPort)
        `when`(fixture.chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L))
            .thenReturn(true)

        val response = fixture.chatService.getMessagesByCursor(
            request = MessagePageRequest(
                chatRoomId = 10L,
                cursor = 10L,
                limit = 50,
                direction = MessageDirection.BEFORE,
            ),
            userId = 7L,
        )

        assertEquals(listOf(9L), response.messages.map { it.roomSeq })
        assertEquals(listOf("before:10:10:50"), readPort.calls)
    }

    @Test
    fun `getMessagesByCursor는 cursorToken이 있으면 numeric cursor보다 우선한다`() {
        val readPort = FakeMessageReadPort(
            before = listOf(messageDto(roomSeq = 9L)),
        )
        val fixture = chatServiceFixture(readPort)
        `when`(fixture.chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L))
            .thenReturn(true)
        val cursorToken = MessageHistoryCursorCodec.encode(
            MessageHistoryCursor(
                createdAt = Instant.parse("2026-06-14T00:00:01Z"),
                roomSeq = 10L,
                messageId = "msg-10",
            ),
        )

        val response = fixture.chatService.getMessagesByCursor(
            request = MessagePageRequest(
                chatRoomId = 10L,
                cursor = 999L,
                cursorToken = cursorToken,
                limit = 50,
                direction = MessageDirection.BEFORE,
            ),
            userId = 7L,
        )

        assertEquals(listOf(9L), response.messages.map { it.roomSeq })
        assertEquals(listOf("before:10:10:50"), readPort.calls)
        assertEquals(
            MessageHistoryCursor(
                createdAt = Instant.parse("2026-06-13T12:00:00Z"),
                roomSeq = 9L,
                messageId = "msg-9",
            ),
            MessageHistoryCursorCodec.decode(response.nextCursorToken),
        )
    }

    @Test
    fun `getMessagesGap은 roomSeq 이후 메시지를 canonical read port에서 조회한다`() {
        val readPort = FakeMessageReadPort(
            gap = listOf(messageDto(roomSeq = 11L), messageDto(roomSeq = 12L)),
        )
        val fixture = chatServiceFixture(readPort)
        `when`(fixture.chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L))
            .thenReturn(true)

        val messages = fixture.chatService.getMessagesGap(
            roomId = 10L,
            userId = 7L,
            afterSeq = 10L,
            limit = 50,
        )

        assertEquals(listOf(11L, 12L), messages.map { it.roomSeq })
        assertEquals(listOf("gap:10:10:50"), readPort.calls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun chatServiceFixture(readPort: MessageReadPort): Fixture {
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
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        val messageRepository = mock(MessageRepository::class.java)
        val webSocketSessionManager = WebSocketSessionManager(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisMessageBroker = redisMessageBroker,
            chatRoomMemberRepository = chatRoomMemberRepository,
            redisProperties = redisProperties,
            gatewayProperties = ChatWebSocketGatewayProperties(),
            outboundExecutor = Runnable::run,
        )

        return Fixture(
            chatService = ChatServiceImpl(
                chatRoomRepository = mock(ChatRoomRepository::class.java),
                messageRepository = messageRepository,
                messageReadPort = readPort,
                chatRoomMemberRepository = chatRoomMemberRepository,
                userRepository = mock(UserRepository::class.java),
                redisMessageBroker = redisMessageBroker,
                messageSequenceService = MessageSequenceService(
                    redisTemplate = redisTemplate,
                    redisProperties = redisProperties,
                    sequenceProperties = MessageSequenceProperties(),
                ),
                messagePersistenceService = MessagePersistenceService(messageRepository),
                webSocketSessionManager = webSocketSessionManager,
                messageStreamProducer = mock(MessageStreamProducer::class.java),
                messageAdmissionPolicyService = MessageAdmissionPolicyService.Noop,
                roomTrafficStatsService = RoomTrafficStatsService.Noop,
            ),
            chatRoomMemberRepository = chatRoomMemberRepository,
        )
    }

    private fun messageDto(roomSeq: Long): MessageDto {
        return MessageDto(
            id = roomSeq,
            messageId = "msg-$roomSeq",
            clientMessageId = "client-$roomSeq",
            chatRoomId = 10L,
            sender = UserDto(
                id = 7L,
                username = "user7",
                displayName = "User 7",
                profileImageUrl = null,
                status = null,
                isActive = true,
                lastSeenAt = null,
                createdAt = LocalDateTime.parse("2026-06-13T11:00:00"),
            ),
            type = MessageType.TEXT,
            content = "hello",
            isEdited = false,
            isDeleted = false,
            createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            editedAt = null,
            sequenceNumber = roomSeq,
            roomSeq = roomSeq,
            writeShard = 1,
        )
    }

    private class FakeMessageReadPort(
        private val page: PageImpl<MessageDto> = PageImpl(emptyList()),
        private val latest: List<MessageDto> = emptyList(),
        private val before: List<MessageDto> = emptyList(),
        private val after: List<MessageDto> = emptyList(),
        private val gap: List<MessageDto> = emptyList(),
        private val byClientMessageId: MessageDto? = null,
    ) : MessageReadPort {
        val calls = mutableListOf<String>()

        override fun findPageByRoom(roomId: Long, pageable: org.springframework.data.domain.Pageable): PageImpl<MessageDto> {
            calls += "page:$roomId:${pageable.pageNumber}:${pageable.pageSize}"
            return page
        }

        override fun findLatestMessages(roomId: Long, limit: Int): List<MessageDto> {
            calls += "latest:$roomId:$limit"
            return latest
        }

        override fun findMessagesBefore(roomId: Long, cursor: Long, limit: Int): List<MessageDto> {
            calls += "before:$roomId:$cursor:$limit"
            return before
        }

        override fun findMessagesAfter(roomId: Long, cursor: Long, limit: Int): List<MessageDto> {
            calls += "after:$roomId:$cursor:$limit"
            return after
        }

        override fun findGapMessages(roomId: Long, afterSeq: Long, limit: Int): List<MessageDto> {
            calls += "gap:$roomId:$afterSeq:$limit"
            return gap
        }

        override fun findLatestMessage(roomId: Long): MessageDto? = null

        override fun findByClientMessageId(roomId: Long, senderId: Long, clientMessageId: String): MessageDto? {
            calls += "client:$roomId:$senderId:$clientMessageId"
            return byClientMessageId
        }
    }

    private data class Fixture(
        val chatService: ChatServiceImpl,
        val chatRoomMemberRepository: ChatRoomMemberRepository,
    )
}
