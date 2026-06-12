package com.chat.persistence.service

import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.Message
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.config.MessageSequenceProperties
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.LocalDateTime
import java.util.Optional

class ChatServiceImplMessageContractTest {

    @Test
    fun `메시지 전송은 Phase 2 envelope 필드를 저장하고 반환한다`() {
        val fixture = chatServiceFixture()
        val clientMessageId = "client-message-1"
        val savedAt = LocalDateTime.parse("2026-06-12T12:00:00")
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())
        `when`(fixture.messageRepository.saveAndFlush(any(Message::class.java))).thenAnswer { invocation ->
            (invocation.arguments[0] as Message).copy(
                id = 101L,
                createdAt = savedAt,
            )
        }

        val message = fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        val savedMessageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(fixture.messageRepository).saveAndFlush(savedMessageCaptor.capture())
        val savedMessage = savedMessageCaptor.value
        assertNotNull(message.messageId)
        assertTrue(message.messageId.isNotBlank())
        assertEquals(clientMessageId, message.clientMessageId)
        assertEquals(1L, message.roomSeq)
        assertEquals(message.roomSeq, message.sequenceNumber)
        assertEquals(message.messageId, savedMessage.messageId)
        assertEquals(clientMessageId, savedMessage.clientMessageId)
        assertEquals(1L, savedMessage.roomSeq)
        assertEquals(0, savedMessage.streamShard)
        assertEquals(0, savedMessage.writeShard)
        assertEquals(0, savedMessage.fanoutShard)
    }

    @Test
    fun `같은 clientMessageId 재전송은 중복 저장 없이 기존 메시지를 반환한다`() {
        val fixture = chatServiceFixture()
        val clientMessageId = "client-message-1"
        val existingMessage = Message(
            id = 101L,
            messageId = "msg-existing",
            clientMessageId = clientMessageId,
            chatRoom = fixture.chatRoom,
            sender = fixture.sender,
            type = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 5L,
            roomSeq = 5L,
            streamShard = 1,
            writeShard = 2,
            fanoutShard = 3,
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.of(existingMessage))

        val message = fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        assertEquals("msg-existing", message.messageId)
        assertEquals(clientMessageId, message.clientMessageId)
        assertEquals(5L, message.roomSeq)
        assertEquals(5L, message.sequenceNumber)
        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
    }

    @Test
    fun `동시 clientMessageId 중복 저장 충돌은 기존 메시지 계약으로 반환한다`() {
        val fixture = chatServiceFixture()
        val clientMessageId = "client-message-1"
        val existingMessage = Message(
            id = 101L,
            messageId = "msg-existing",
            clientMessageId = clientMessageId,
            chatRoom = fixture.chatRoom,
            sender = fixture.sender,
            type = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 5L,
            roomSeq = 5L,
            streamShard = 1,
            writeShard = 2,
            fanoutShard = 3,
            createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
        )
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty(), Optional.of(existingMessage))
        `when`(fixture.messageRepository.saveAndFlush(any(Message::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate client message"))

        val message = fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        assertEquals("msg-existing", message.messageId)
        assertEquals(clientMessageId, message.clientMessageId)
        assertEquals(5L, message.roomSeq)
        assertEquals(5L, message.sequenceNumber)
    }

    @Suppress("UNCHECKED_CAST")
    private fun chatServiceFixture(): Fixture {
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.increment("chat:sequence:10", 1000L)).thenReturn(1000L)

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

        val sender = user(7L)
        val chatRoom = chatRoom(10L)
        val member = ChatRoomMember(chatRoom = chatRoom, user = sender)

        val chatRoomRepository = mock(ChatRoomRepository::class.java)
        val userRepository = mock(UserRepository::class.java)
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        val messageRepository = mock(MessageRepository::class.java)
        `when`(chatRoomRepository.findById(10L)).thenReturn(Optional.of(chatRoom))
        `when`(userRepository.findById(7L)).thenReturn(Optional.of(sender))
        `when`(chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L))
            .thenReturn(Optional.of(member))

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
                chatRoomRepository = chatRoomRepository,
                messageRepository = messageRepository,
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
            ),
            messageRepository = messageRepository,
            chatRoom = chatRoom,
            sender = sender,
        )
    }

    private fun user(id: Long): User {
        return User(
            id = id,
            username = "user$id",
            password = "password",
            displayName = "User $id",
        )
    }

    private fun chatRoom(id: Long): ChatRoom {
        return ChatRoom(
            id = id,
            name = "room-$id",
            createdBy = user(1L),
        )
    }

    private data class Fixture(
        val chatService: ChatServiceImpl,
        val messageRepository: MessageRepository,
        val chatRoom: ChatRoom,
        val sender: User,
    )
}
