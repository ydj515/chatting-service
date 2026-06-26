package com.chat.persistence.service

import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.exception.MessageAdmissionRejectedException
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.MemberRole
import com.chat.domain.model.Message
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.ChatWebSocketGatewayProperties
import com.chat.persistence.config.MessageSequenceProperties
import com.chat.persistence.redis.MessageStreamEnvelope
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
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
    fun `메시지 수락 정책과 traffic stats 의존성은 constructor default로 fail open 되지 않는다`() {
        val hasDefaultConstructorBridge = ChatServiceImpl::class.java.declaredConstructors.any { constructor ->
            constructor.parameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
        }

        assertFalse(hasDefaultConstructorBridge)
    }

    @Test
    fun `메시지 전송은 Streams append 이후 동기 저장 없이 수락 계약을 반환한다`() {
        val fixture = chatServiceFixture()
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        val message = fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
        assertEquals(0L, message.id)
        assertNotNull(message.messageId)
        assertTrue(message.messageId.isNotBlank())
        assertEquals(clientMessageId, message.clientMessageId)
        assertEquals(1L, message.roomSeq)
        assertEquals(message.roomSeq, message.sequenceNumber)
    }

    @Test
    fun `메시지 전송은 Redis Streams append 이후 API에서 직접 fanout하지 않는다`() {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        `when`(messageStreamProducer.append(anyMessageStreamEnvelope()))
            .thenReturn("1749790000000-0")
        val fixture = chatServiceFixture(messageStreamProducer = messageStreamProducer)
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        val message = fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        val envelopeCaptor = ArgumentCaptor.forClass(MessageStreamEnvelope::class.java)
        val inOrder = inOrder(messageStreamProducer, fixture.messageRepository)
        inOrder.verify(messageStreamProducer).append(captureMessageStreamEnvelope(envelopeCaptor))
        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
        verify(fixture.redisTemplate, never()).convertAndSend(anyString(), anyString())

        val envelope = envelopeCaptor.value
        assertEquals(message.messageId, envelope.messageId)
        assertEquals(clientMessageId, envelope.clientMessageId)
        assertEquals(10L, envelope.chatRoomId)
        assertEquals(7L, envelope.senderId)
        assertEquals("User 7", envelope.senderName)
        assertEquals(MessageType.TEXT, envelope.messageType)
        assertEquals("hello", envelope.content)
        assertEquals(1L, envelope.roomSeq)
        assertEquals(1L, envelope.sequenceNumber)
        assertEquals(0, envelope.streamShard)
        assertEquals(0, envelope.writeShard)
        assertEquals(0, envelope.fanoutShard)
    }

    @Test
    fun `메시지 전송은 Streams append 성공 후 room traffic counter를 기록한다`() {
        val roomTrafficStatsService = RecordingRoomTrafficStatsService()
        val fixture = chatServiceFixture(roomTrafficStatsService = roomTrafficStatsService)
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        assertEquals(listOf(10L), roomTrafficStatsService.recordedRoomIds)
    }

    @Test
    fun `Streams append 실패 시 room traffic counter를 기록하지 않는다`() {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        `when`(messageStreamProducer.append(anyMessageStreamEnvelope()))
            .thenThrow(IllegalStateException("stream append failed"))
        val roomTrafficStatsService = RecordingRoomTrafficStatsService()
        val fixture = chatServiceFixture(
            messageStreamProducer = messageStreamProducer,
            roomTrafficStatsService = roomTrafficStatsService,
        )
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) {
            fixture.chatService.sendMessage(
                SendMessageRequest(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "hello",
                    clientMessageId = clientMessageId,
                ),
                senderId = 7L,
            )
        }

        assertEquals(emptyList<Long>(), roomTrafficStatsService.recordedRoomIds)
    }

    @Test
    fun `room traffic counter 기록 실패는 이미 수락된 메시지 응답을 실패시키지 않는다`() {
        val fixture = chatServiceFixture(roomTrafficStatsService = ThrowingRoomTrafficStatsService())
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        val message = fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        assertEquals(clientMessageId, message.clientMessageId)
        assertEquals(1L, message.roomSeq)
    }


    @Test
    fun `Redis Streams append 실패 시 메시지를 저장하거나 fanout하지 않는다`() {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        `when`(messageStreamProducer.append(anyMessageStreamEnvelope()))
            .thenThrow(IllegalStateException("stream append failed"))
        val fixture = chatServiceFixture(messageStreamProducer = messageStreamProducer)
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) {
            fixture.chatService.sendMessage(
                SendMessageRequest(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "hello",
                    clientMessageId = clientMessageId,
                ),
                senderId = 7L,
            )
        }

        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
        verify(fixture.redisTemplate, never()).convertAndSend(anyString(), anyString())
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
    fun `메시지 수락 정책 거부 시 sequence 발급과 stream append를 수행하지 않는다`() {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        val admissionPolicyService = RejectingMessageAdmissionPolicyService("room rate limit exceeded")
        val fixture = chatServiceFixture(
            messageStreamProducer = messageStreamProducer,
            messageAdmissionPolicyService = admissionPolicyService,
        )
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        val exception = assertThrows(MessageAdmissionRejectedException::class.java) {
            fixture.chatService.sendMessage(
                SendMessageRequest(
                    chatRoomId = 10L,
                    type = MessageType.TEXT,
                    content = "hello",
                    clientMessageId = clientMessageId,
                ),
                senderId = 7L,
            )
        }

        assertEquals("room rate limit exceeded", exception.message)
        verify(messageStreamProducer, never()).append(anyMessageStreamEnvelope())
        verify(fixture.redisTemplate.opsForValue(), never()).increment("chat:sequence:10", 1L)
        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
    }

    @Test
    fun `같은 clientMessageId 재전송은 메시지 수락 정책을 다시 검사하지 않는다`() {
        val admissionPolicyService = RecordingMessageAdmissionPolicyService()
        val fixture = chatServiceFixture(messageAdmissionPolicyService = admissionPolicyService)
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

        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        assertEquals(0, admissionPolicyService.callCount)
    }

    @Test
    fun `메시지 수락 정책은 채팅방 멤버 역할을 함께 전달받는다`() {
        val admissionPolicyService = RecordingMessageAdmissionPolicyService()
        val fixture = chatServiceFixture(
            messageAdmissionPolicyService = admissionPolicyService,
            memberRole = MemberRole.ADMIN,
        )
        val clientMessageId = "client-message-1"
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                clientMessageId,
            )
        ).thenReturn(Optional.empty())

        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )

        assertEquals(MemberRole.ADMIN, admissionPolicyService.lastMemberRole)
    }

    @Test
    fun `메시지 전송은 방별 shard config로 stream과 fanout shard를 roomSeq round robin으로 계산한다`() {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        `when`(messageStreamProducer.append(anyMessageStreamEnvelope()))
            .thenReturn("1749790000000-0")
            .thenReturn("1749790000001-0")
        val shardReader = FixedRoomStorageConfigReader(
            RoomShardConfig(writeShardCount = 4, fanoutShardCount = 16),
        )
        val fixture = chatServiceFixture(
            messageStreamProducer = messageStreamProducer,
            roomStorageConfigReader = shardReader,
            sequenceValues = listOf(1L, 2L),
        )
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                "client-message-1",
            )
        ).thenReturn(Optional.empty())
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                "client-message-2",
            )
        ).thenReturn(Optional.empty())

        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello-1",
                clientMessageId = "client-message-1",
            ),
            senderId = 7L,
        )
        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello-2",
                clientMessageId = "client-message-2",
            ),
            senderId = 7L,
        )

        val envelopeCaptor = ArgumentCaptor.forClass(MessageStreamEnvelope::class.java)
        verify(messageStreamProducer, times(2)).append(captureMessageStreamEnvelope(envelopeCaptor))
        val envelopes = envelopeCaptor.allValues
        assertEquals(listOf(0, 1), envelopes.map { it.streamShard })
        assertEquals(listOf(0, 1), envelopes.map { it.fanoutShard })
        envelopes.forEach { envelope ->
            assertTrue(envelope.writeShard in 0..3)
        }
        assertEquals(listOf(10L, 10L), shardReader.shardConfigRoomIds)
    }


    @Suppress("UNCHECKED_CAST")
    private fun chatServiceFixture(
        messageStreamProducer: MessageStreamProducer = successfulMessageStreamProducer(),
        messageAdmissionPolicyService: MessageAdmissionPolicyService = MessageAdmissionPolicyService.Noop,
        roomTrafficStatsService: RoomTrafficStatsService = RoomTrafficStatsService.Noop,
        memberRole: MemberRole = MemberRole.MEMBER,
        roomStorageConfigReader: RoomStorageConfigReader = FixedRoomStorageConfigReader(RoomShardConfig()),
        sequenceValues: List<Long> = listOf(1L),
    ): Fixture {
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.increment("chat:sequence:10", 1L))
            .thenReturn(sequenceValues.first(), *sequenceValues.drop(1).toTypedArray())

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
        val member = ChatRoomMember(chatRoom = chatRoom, user = sender, role = memberRole)

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
                messageStreamProducer = messageStreamProducer,
                messageAdmissionPolicyService = messageAdmissionPolicyService,
                roomTrafficStatsService = roomTrafficStatsService,
                roomStorageConfigReader = roomStorageConfigReader,
            ),
            messageRepository = messageRepository,
            chatRoom = chatRoom,
            sender = sender,
            redisTemplate = redisTemplate,
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

    private fun anyMessageStreamEnvelope(): MessageStreamEnvelope {
        any(MessageStreamEnvelope::class.java)
        return uninitialized()
    }

    private fun captureMessageStreamEnvelope(
        captor: ArgumentCaptor<MessageStreamEnvelope>,
    ): MessageStreamEnvelope {
        captor.capture()
        return uninitialized()
    }

    private fun successfulMessageStreamProducer(): MessageStreamProducer {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        `when`(messageStreamProducer.append(anyMessageStreamEnvelope())).thenReturn("1749790000000-0")
        return messageStreamProducer
    }

    private class RejectingMessageAdmissionPolicyService(
        private val message: String,
    ) : MessageAdmissionPolicyService {

        override fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole) {
            throw MessageAdmissionRejectedException(message)
        }
    }

    private class RecordingMessageAdmissionPolicyService : MessageAdmissionPolicyService {
        var callCount: Int = 0
            private set
        var lastMemberRole: MemberRole? = null
            private set

        override fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole) {
            callCount += 1
            lastMemberRole = memberRole
        }
    }

    private class FixedRoomStorageConfigReader(
        private val config: RoomShardConfig,
    ) : RoomStorageConfigReader {
        val shardConfigRoomIds = mutableListOf<Long>()

        override fun currentShardCount(roomId: Long): Int {
            error("sendMessage must read shardConfig(roomId), not currentShardCount(roomId)")
        }

        override fun shardConfig(roomId: Long): RoomShardConfig {
            shardConfigRoomIds += roomId
            return config
        }
    }

    private class RecordingRoomTrafficStatsService : RoomTrafficStatsService {
        val recordedRoomIds = mutableListOf<Long>()

        override fun recordAccepted(roomId: Long) {
            recordedRoomIds += roomId
        }

        override fun activeRoomIds(): Set<Long> = emptySet()

        override fun snapshot(roomId: Long): RoomTrafficSnapshot {
            return RoomTrafficSnapshot(
                roomId = roomId,
                roomMessagesPerSecond = 0,
                roomMessagesP95PerSecond = 0,
            )
        }
    }

    private class ThrowingRoomTrafficStatsService : RoomTrafficStatsService {
        override fun recordAccepted(roomId: Long) {
            throw IllegalStateException("traffic stats unavailable")
        }

        override fun activeRoomIds(): Set<Long> = emptySet()

        override fun snapshot(roomId: Long): RoomTrafficSnapshot {
            return RoomTrafficSnapshot(
                roomId = roomId,
                roomMessagesPerSecond = 0,
                roomMessagesP95PerSecond = 0,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T

    private data class Fixture(
        val chatService: ChatServiceImpl,
        val messageRepository: MessageRepository,
        val chatRoom: ChatRoom,
        val sender: User,
        val redisTemplate: RedisTemplate<String, String>,
    )
}
