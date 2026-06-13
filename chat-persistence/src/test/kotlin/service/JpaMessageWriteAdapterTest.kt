package com.chat.persistence.service

import com.chat.domain.model.ChatRoom
import com.chat.domain.model.Message
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime
import java.util.Optional

class JpaMessageWriteAdapterTest {

    @Test
    fun `jpa adapter는 신규 메시지를 compatibility table에 batch 저장한다`() {
        val fixture = adapterFixture()
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
        `when`(fixture.messageRepository.saveAllAndFlush(anyMessageList())).thenAnswer { invocation ->
            invocation.arguments[0]
        }

        val result = fixture.adapter.write(listOf(writeRequest()))

        assertEquals(1, result.writtenCount)
        assertEquals(true, result.outcomes.single().written)

        val messagesCaptor = messageListCaptor()
        verify(fixture.messageRepository).saveAllAndFlush(captureMessageList(messagesCaptor))
        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
        val savedMessage = messagesCaptor.value.single()
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
    fun `jpa adapter는 이미 저장된 messageId를 duplicate outcome으로 반환한다`() {
        val fixture = adapterFixture()
        val existing = existingMessage(fixture)
        `when`(fixture.messageRepository.findByMessageId("msg-1")).thenReturn(Optional.of(existing))

        val result = fixture.adapter.write(listOf(writeRequest()))

        assertEquals(0, result.writtenCount)
        assertEquals(false, result.outcomes.single().written)
        verify(fixture.messageRepository, never()).saveAllAndFlush(anyMessageList())
        verify(fixture.messageRepository, never()).saveAndFlush(any(Message::class.java))
    }

    @Test
    fun `jpa adapter는 concurrent duplicate insert 충돌 시 재조회 후 duplicate outcome을 반환한다`() {
        val fixture = adapterFixture()
        val existing = existingMessage(fixture)
        `when`(fixture.messageRepository.findByMessageId("msg-1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existing))
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                "client-1",
            )
        ).thenReturn(Optional.empty())
        `when`(fixture.chatRoomRepository.getReferenceById(10L)).thenReturn(fixture.chatRoom)
        `when`(fixture.userRepository.getReferenceById(7L)).thenReturn(fixture.sender)
        `when`(fixture.messageRepository.saveAllAndFlush(anyMessageList()))
            .thenThrow(DataIntegrityViolationException("duplicate"))
        `when`(fixture.messageRepository.saveAndFlush(any(Message::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate"))

        val result = fixture.adapter.write(listOf(writeRequest()))

        assertEquals(0, result.writtenCount)
        assertEquals(false, result.outcomes.single().written)
        verify(fixture.messageRepository).saveAndFlush(any(Message::class.java))
    }

    private fun adapterFixture(): Fixture {
        val messageRepository = mock(MessageRepository::class.java)
        val chatRoomRepository = mock(ChatRoomRepository::class.java)
        val userRepository = mock(UserRepository::class.java)
        val chatRoom = ChatRoom(
            id = 10L,
            name = "room-10",
            createdBy = User(
                id = 1L,
                username = "owner",
                password = "password",
                displayName = "Owner",
            ),
        )
        val sender = User(
            id = 7L,
            username = "user7",
            password = "password",
            displayName = "User 7",
        )

        return Fixture(
            adapter = JpaMessageWriteAdapter(
                messageRepository = messageRepository,
                chatRoomRepository = chatRoomRepository,
                userRepository = userRepository,
            ),
            messageRepository = messageRepository,
            chatRoomRepository = chatRoomRepository,
            userRepository = userRepository,
            chatRoom = chatRoom,
            sender = sender,
        )
    }

    private fun writeRequest(): MessageWriteRequest {
        return MessageWriteRequest(
            messageId = "msg-1",
            clientMessageId = "client-1",
            chatRoomId = 10L,
            senderId = 7L,
            messageType = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 11L,
            roomSeq = 11L,
            streamShard = 0,
            writeShard = 1,
            fanoutShard = 2,
            createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
        )
    }

    private fun existingMessage(fixture: Fixture): Message {
        return Message(
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
    }

    @Suppress("UNCHECKED_CAST")
    private fun messageListCaptor(): ArgumentCaptor<List<Message>> {
        return ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Message>>
    }

    private fun anyMessageList(): List<Message> {
        anyList<Message>()
        return uninitialized()
    }

    private fun captureMessageList(captor: ArgumentCaptor<List<Message>>): List<Message> {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T

    private data class Fixture(
        val adapter: JpaMessageWriteAdapter,
        val messageRepository: MessageRepository,
        val chatRoomRepository: ChatRoomRepository,
        val userRepository: UserRepository,
        val chatRoom: ChatRoom,
        val sender: User,
    )
}
