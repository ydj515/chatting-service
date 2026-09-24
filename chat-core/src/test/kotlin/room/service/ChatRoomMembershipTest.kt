package com.chat.core.room.service

import com.chat.core.message.port.MessageReadPort
import com.chat.core.message.service.MessageSendingService
import com.chat.core.room.port.ChatMembershipStore
import com.chat.core.room.port.ChatRoomStore
import com.chat.core.room.port.MembershipEvents
import com.chat.core.user.port.UserStore
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.User
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class ChatRoomMembershipTest {
    private val rooms = mock(ChatRoomStore::class.java)
    private val members = mock(ChatMembershipStore::class.java)
    private val users = mock(UserStore::class.java)
    private val events = mock(MembershipEvents::class.java)
    private val service = ChatServiceImpl(rooms, mock(MessageReadPort::class.java), members, users, mock(MessageSendingService::class.java), events)
    private val user = User(id = 7, username = "tester", password = "unused", displayName = "Tester")
    private val room = ChatRoom(id = 10, name = "room", maxMembers = 2, createdBy = user)

    @Test
    fun `reactivation locks the room before checking capacity and publishing membership`() {
        `when`(rooms.findByIdForMembershipUpdate(10)).thenReturn(room)
        `when`(users.findById(7)).thenReturn(user)
        `when`(members.countActiveMembersInRoom(10)).thenReturn(1)
        `when`(members.reactivateMembership(10, 7)).thenReturn(1)
        service.joinChatRoom(10, 7)
        inOrder(rooms, members, events).apply {
            verify(rooms).findByIdForMembershipUpdate(10)
            verify(members).countActiveMembersInRoom(10)
            verify(members).reactivateMembership(10, 7)
            verify(events).joinedAfterCommit(7, 10)
        }
    }

    @Test
    fun `full room rejects the join without reactivation or notification`() {
        `when`(rooms.findByIdForMembershipUpdate(10)).thenReturn(room)
        `when`(users.findById(7)).thenReturn(user)
        `when`(members.countActiveMembersInRoom(10)).thenReturn(2)
        assertThrows(ResourceConflictException::class.java) { service.joinChatRoom(10, 7) }
        verify(members, never()).reactivateMembership(10, 7)
        verifyNoInteractions(events)
    }
}
