package com.chat.persistence.service

import com.chat.core.service.SessionTokenService
import com.chat.core.user.port.LoginSanctionReader
import com.chat.core.user.port.PasswordHashing
import com.chat.core.user.port.UserStore
import com.chat.core.user.service.UserServiceImpl
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

class UserLogoutTransactionTest {
    @Test
    fun `logout suspends caller transaction and restores it after revocation failure`() {
        val sessions = mock(SessionTokenService::class.java)
        `when`(sessions.revokeToken("token")).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            error("Revocation unavailable")
        }
        val target = UserServiceImpl(mock(UserStore::class.java), sessions, mock(LoginSanctionReader::class.java), Clock.systemUTC(), mock(PasswordHashing::class.java))
        val manager = DataSourceTransactionManager(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()}", "sa", ""))
        val interceptor = TransactionInterceptor().apply {
            transactionManager = manager
            transactionAttributeSource = AnnotationTransactionAttributeSource()
        }
        val proxy = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(interceptor)
        }.proxy as UserServiceImpl

        TransactionTemplate(manager).executeWithoutResult {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            assertThrows(IllegalStateException::class.java) { proxy.logout("token") }
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        }
        verify(sessions).revokeToken("token")
    }
}
