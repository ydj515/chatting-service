package com.chat.persistence.service

import com.chat.domain.model.ChatRoom
import com.chat.domain.model.Message
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.Optional

class MessageWriterWorkerTest {

    @Test
    fun `writer worker는 stream consumer group으로 읽은 메시지를 compatibility table에 저장하고 ack한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord()),
        )
        val fixture = workerFixture(consumer)
        `when`(fixture.messageRepository.findByMessageId("msg-1")).thenReturn(Optional.empty())
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                "client-1",
            )
        ).thenReturn(Optional.empty())
        `when`(fixture.chatRoomRepository.getReferenceById(10L)).thenReturn(fixture.chatRoom)
        `when`(fixture.userRepository.getReferenceById(7L)).thenReturn(fixture.sender)
        `when`(fixture.messageRepository.saveAndFlush(any(Message::class.java))).thenAnswer { invocation ->
            (invocation.arguments[0] as Message).copy(id = 101L)
        }

        val written = fixture.worker.pollAndWrite()

        assertEquals(1, written)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer"), consumer.ensuredGroups)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:worker-1"), consumer.reads)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-0"), consumer.acked)

        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(fixture.messageRepository).saveAndFlush(messageCaptor.capture())
        val savedMessage = messageCaptor.value
        assertEquals("msg-1", savedMessage.messageId)
        assertEquals("client-1", savedMessage.clientMessageId)
        assertEquals(10L, savedMessage.chatRoom.id)
        assertEquals(7L, savedMessage.sender.id)
        assertEquals(MessageType.TEXT, savedMessage.type)
        assertEquals("hello", savedMessage.content)
        assertEquals(11L, savedMessage.sequenceNumber)
        assertEquals(11L, savedMessage.roomSeq)
        assertEquals(0, savedMessage.streamShard)
        assertEquals(1, savedMessage.writeShard)
        assertEquals(2, savedMessage.fanoutShard)
        assertEquals(LocalDateTime.parse("2026-06-13T12:00:00"), savedMessage.createdAt)
    }

    @Test
    fun `writer worker는 이미 저장된 messageId를 다시 저장하지 않고 ack한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord()),
        )
        val fixture = workerFixture(consumer)
        val existing = Message(
            id = 101L,
            messageId = "msg-1",
            clientMessageId = "client-1",
            chatRoom = fixture.chatRoom,
            sender = fixture.sender,
            type = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 11L,
            roomSeq = 11L,
        )
        `when`(fixture.messageRepository.findByMessageId("msg-1")).thenReturn(Optional.of(existing))

        val written = fixture.worker.pollAndWrite()

        assertEquals(0, written)
        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-0"), consumer.acked)
    }

    private fun workerFixture(consumer: MessageStreamConsumer): Fixture {
        val messageRepository = mock(MessageRepository::class.java)
        val chatRoomRepository = mock(ChatRoomRepository::class.java)
        val userRepository = mock(UserRepository::class.java)
        val worker = MessageWriterWorker(
            messageStreamConsumer = consumer,
            messageRepository = messageRepository,
            chatRoomRepository = chatRoomRepository,
            userRepository = userRepository,
            workerProperties = ChatWorkerProperties(
                consumerName = "worker-1",
                writer = ChatWorkerProperties.StreamConsumer(
                    consumerGroup = "message-writer",
                    readCount = 10,
                ),
            ),
        )

        return Fixture(
            worker = worker,
            messageRepository = messageRepository,
            chatRoomRepository = chatRoomRepository,
            userRepository = userRepository,
            chatRoom = ChatRoom(
                id = 10L,
                name = "room-10",
                createdBy = User(
                    id = 1L,
                    username = "owner",
                    password = "password",
                    displayName = "Owner",
                ),
            ),
            sender = User(
                id = 7L,
                username = "user7",
                password = "password",
                displayName = "User 7",
            ),
        )
    }

    private fun streamRecord(): MessageStreamRecord {
        return MessageStreamRecord(
            streamKey = "chat:stream:room:10:shard:0",
            recordId = "1749790000000-0",
            envelope = MessageStreamEnvelope(
                messageId = "msg-1",
                clientMessageId = "client-1",
                chatRoomId = 10L,
                senderId = 7L,
                senderName = "User 7",
                messageType = MessageType.TEXT,
                content = "hello",
                sequenceNumber = 11L,
                roomSeq = 11L,
                streamShard = 0,
                writeShard = 1,
                fanoutShard = 2,
                createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            ),
        )
    }

    private class FakeMessageStreamConsumer(
        private val records: List<MessageStreamRecord>,
    ) : MessageStreamConsumer {
        val ensuredGroups = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val acked = mutableListOf<String>()

        override fun listStreamKeys(): Set<String> {
            return records.mapTo(sortedSetOf()) { it.streamKey }
        }

        override fun ensureConsumerGroup(streamKey: String, consumerGroup: String) {
            ensuredGroups += "$streamKey:$consumerGroup"
        }

        override fun readNew(
            consumerGroup: String,
            consumerName: String,
            streamKeys: Set<String>,
            count: Long,
        ): List<MessageStreamRecord> {
            reads += "${streamKeys.first()}:$consumerGroup:$consumerName"
            return records
        }

        override fun acknowledge(streamKey: String, consumerGroup: String, recordId: String) {
            acked += "$streamKey:$consumerGroup:$recordId"
        }
    }

    private data class Fixture(
        val worker: MessageWriterWorker,
        val messageRepository: MessageRepository,
        val chatRoomRepository: ChatRoomRepository,
        val userRepository: UserRepository,
        val chatRoom: ChatRoom,
        val sender: User,
    )
}
