package com.chat.persistence.service

import com.chat.persistence.redis.RedisMessageBroker
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class MembershipEventsTransactionTest {
    private val broker = mock(RedisMessageBroker::class.java)
    private val sessions = mock(WebSocketSessionManager::class.java)
    private val events = MembershipEventPublisher(broker, sessions)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()}", "sa", "")))

    @Test
    fun `joining notifies local and remote subscribers only after commit`() {
        transaction.executeWithoutResult {
            events.joinedAfterCommit(7, 10)
            verifyNoInteractions(broker, sessions)
        }
        verify(sessions).isUserOnlineLocally(7)
        verify(broker).publishMembershipChanged(7, 10, RedisMessageBroker.MembershipAction.JOIN)
    }

    @Test
    fun `rolled back leave never changes subscriptions or publishes an event`() {
        transaction.executeWithoutResult { status ->
            events.leftAfterCommit(7, 10)
            status.setRollbackOnly()
            verifyNoInteractions(broker, sessions)
        }
        verifyNoInteractions(broker, sessions)
    }
}
