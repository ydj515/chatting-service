package com.chat.persistence.service

import com.chat.domain.model.ChatRoom
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
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.Optional

class ChatServiceImplMembershipEventTest {
    @Test
    fun `채팅방 참여는 JOIN membership event를 발행한다`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        val chatRoomRepository = mock(ChatRoomRepository::class.java)
        val userRepository = mock(UserRepository::class.java)
        val redisTemplate = redisTemplate()
        val chatRoom = chatRoom(id = 10L)
        val user = user(id = 7L)
        `when`(chatRoomRepository.findByIdForMembershipUpdate(10L)).thenReturn(chatRoom)
        `when`(userRepository.findById(7L)).thenReturn(Optional.of(user))
        `when`(chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(10L, 7L)).thenReturn(false)
        val chatService = chatService(
            chatRoomMemberRepository = chatRoomMemberRepository,
            redisTemplate = redisTemplate,
            chatRoomRepository = chatRoomRepository,
            userRepository = userRepository,
        )

        chatService.joinChatRoom(roomId = 10L, userId = 7L)

        verify(redisTemplate).convertAndSend(eq("chat.membership"), contains("\"action\":\"JOIN\""))
    }

    @Test
    fun `채팅방 퇴장은 LEAVE membership event를 발행한다`() {
        val chatRoomMemberRepository = mock(ChatRoomMemberRepository::class.java)
        val redisTemplate = redisTemplate()
        val chatService = chatService(chatRoomMemberRepository, redisTemplate)

        chatService.leaveChatRoom(roomId = 10L, userId = 7L)

        verify(chatRoomMemberRepository).leaveChatRoom(10L, 7L)
        verify(redisTemplate).convertAndSend(eq("chat.membership"), contains("\"action\":\"LEAVE\""))
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisTemplate<String, String> =
        mock(RedisTemplate::class.java) as RedisTemplate<String, String>

    private fun chatService(
        chatRoomMemberRepository: ChatRoomMemberRepository,
        redisTemplate: RedisTemplate<String, String>,
        chatRoomRepository: ChatRoomRepository = mock(ChatRoomRepository::class.java),
        userRepository: UserRepository = mock(UserRepository::class.java),
    ): ChatServiceImpl {
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
                gatewayProperties = ChatWebSocketGatewayProperties(),
                outboundExecutor = Runnable::run,
            ),
        )

        val messageRepository = mock(MessageRepository::class.java)

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

    private fun user(id: Long): User =
        User(
            id = id,
            username = "user$id",
            password = "password",
            displayName = "User $id",
        )

    private fun chatRoom(id: Long): ChatRoom =
        ChatRoom(
            id = id,
            name = "room-$id",
            createdBy = user(id = 1L),
        )
}
