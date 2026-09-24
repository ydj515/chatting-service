package com.chat.core.message.service

import com.chat.core.dto.MessageDto
import com.chat.core.dto.SendMessageRequest
import com.chat.core.message.port.MessageAcceptance
import com.chat.core.message.port.MessageReadPort
import com.chat.core.room.port.ChatMembershipStore
import com.chat.core.room.port.ChatRoomStore
import com.chat.core.user.port.UserStore
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.MessageType
import com.chat.domain.model.User
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class MessageSendingServiceTest {
    private val rooms = mock(ChatRoomStore::class.java)
    private val users = mock(UserStore::class.java)
    private val members = mock(ChatMembershipStore::class.java)
    private val messages = mock(MessageReadPort::class.java)
    private val policy = mock(MessageSendPolicy::class.java)
    private val acceptance = mock(MessageAcceptance::class.java)
    private val service = MessageSendingService(rooms, users, members, messages, policy, acceptance)
    private val sender = User(id = 7, username = "tester", password = "unused", displayName = "Tester")
    private val room = ChatRoom(id = 10, name = "room", createdBy = sender)
    private val request = SendMessageRequest(10, MessageType.TEXT, "hello", " client-id ")

    @Test
    fun `persisted duplicate does not consume policy or acceptance again`() {
        allowMembership()
        val existing = mock(MessageDto::class.java)
        `when`(messages.findByClientMessageId(10, 7, "client-id")).thenReturn(existing)
        assertSame(existing, service.sendMessage(request, 7))
        verifyNoInteractions(policy, acceptance)
    }

    @Test
    fun `accepted duplicate does not consume policy or append again`() {
        allowMembership()
        val existing = mock(MessageDto::class.java)
        `when`(acceptance.findAccepted(10, sender, "client-id")).thenReturn(existing)
        assertSame(existing, service.sendMessage(request, 7))
        verifyNoInteractions(policy)
        verify(acceptance).findAccepted(10, sender, "client-id")
        verifyNoMoreInteractions(acceptance)
    }

    private fun allowMembership() {
        `when`(rooms.findById(10)).thenReturn(room)
        `when`(users.findById(7)).thenReturn(sender)
        `when`(members.findByChatRoomIdAndUserIdAndIsActiveTrue(10, 7)).thenReturn(ChatRoomMember(chatRoom = room, user = sender))
    }
}
