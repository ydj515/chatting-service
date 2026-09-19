package com.chat.persistence.service

import com.chat.domain.exception.ForbiddenOperationException
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.User
import com.chat.persistence.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.cache.annotation.AnnotationCacheOperationSource
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.cache.interceptor.CacheInterceptor
import java.util.Optional

class ChatRoomAuthorizationTest {
    private val rooms = mock(ChatRoomRepository::class.java)
    private val members = mock(ChatRoomMemberRepository::class.java)
    private val messages = mock(MessageReadPort::class.java)
    private val room = ChatRoom(id = 10, name = "room", createdBy = User(id = 7, username = "owner", password = "synthetic", displayName = "Owner"))
    private val service = ChatServiceImpl(rooms, messages, members, mock(UserRepository::class.java), mock(MessageSendingService::class.java), mock(MembershipEventPublisher::class.java))

    @Test
    fun `nonmembers cannot read room details or membership`() {
        assertThrows(ForbiddenOperationException::class.java) { service.getChatRoom(10, 8) }
        assertThrows(ForbiddenOperationException::class.java) { service.getChatRoomMembers(10, 8) }
        verifyNoInteractions(rooms, messages)
    }

    @Test
    fun `cached room and member data never bypass revocation of membership`() {
        `when`(members.existsByChatRoomIdAndUserIdAndIsActiveTrue(10, 7)).thenReturn(true)
        `when`(rooms.findById(10)).thenReturn(Optional.of(room))
        `when`(members.findByChatRoomIdAndIsActiveTrue(10)).thenReturn(emptyList())
        val cacheManager = ConcurrentMapCacheManager("chatRooms", "chatRoomMembers")
        checkNotNull(cacheManager.getCache("chatRooms")).put(10L, service.getChatRoom(10, 7))
        checkNotNull(cacheManager.getCache("chatRoomMembers")).put(10L, service.getChatRoomMembers(10, 7))
        val advice = CacheInterceptor().apply {
            setCacheManager(cacheManager)
            setCacheOperationSources(AnnotationCacheOperationSource())
            afterPropertiesSet()
            afterSingletonsInstantiated()
        }
        val proxy = ProxyFactory(service).apply { addAdvice(advice) }.proxy as com.chat.domain.service.ChatService
        `when`(members.existsByChatRoomIdAndUserIdAndIsActiveTrue(10, 7)).thenReturn(false)
        assertThrows(ForbiddenOperationException::class.java) { proxy.getChatRoom(10, 7) }
        assertThrows(ForbiddenOperationException::class.java) { proxy.getChatRoomMembers(10, 7) }
    }

    @Test
    fun `discovery returns metadata without loading protected messages`() {
        `when`(rooms.findByIsActiveTrueOrderByCreatedAtDesc()).thenReturn(listOf(room))
        val results = service.searchChatRooms("", 8)
        assertEquals(1, results.size)
        assertNull(results.single().lastMessage)
        verifyNoInteractions(messages)
    }
}
