package com.chat.persistence.service

import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.exception.ResourceNotFoundException
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.MessageType
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.UUID

class MessageSendingTransactionTest {
    @Test
    fun `send boundary suspends an outer transaction and restores it on failure`() {
        val rooms = mock(ChatRoomRepository::class.java)
        `when`(rooms.findById(10)).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            Optional.empty<ChatRoom>()
        }
        val target = MessageSendingService(rooms, mock(UserRepository::class.java), mock(ChatRoomMemberRepository::class.java), mock(MessageReadPort::class.java), mock(MessageSendPolicy::class.java), mock(MessageStreamPublisher::class.java))
        val manager = DataSourceTransactionManager(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()}", "sa", ""))
        val proxy = ProxyFactory(target).apply {
            addAdvice(TransactionInterceptor(manager, AnnotationTransactionAttributeSource()))
        }.proxy as MessageSendingService
        TransactionTemplate(manager).executeWithoutResult {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            assertThrows(ResourceNotFoundException::class.java) { proxy.sendMessage(SendMessageRequest(10, MessageType.TEXT, "hello"), 7) }
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        }
        verify(rooms).findById(10)
    }
}
