package com.chat.persistence.service

import com.chat.core.service.SessionTokenService
import com.chat.core.service.UserService
import com.chat.core.user.service.UserServiceImpl
import com.chat.persistence.repository.LoginSanctionAdapter
import com.chat.persistence.repository.UserRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserStoreAdapter
import com.chat.persistence.security.PasswordHashingAdapter
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.util.function.Supplier

class UserUseCaseWiringTest {
    @Test
    fun `core user service resolves required infrastructure adapters`() {
        AnnotationConfigApplicationContext().use { context ->
            context.register(UserServiceImpl::class.java, UserStoreAdapter::class.java, LoginSanctionAdapter::class.java, PasswordHashingAdapter::class.java)
            context.registerBean("users", UserRepository::class.java, Supplier { mock(UserRepository::class.java) })
            context.registerBean("sanctions", UserSanctionJdbcRepository::class.java, Supplier { mock(UserSanctionJdbcRepository::class.java) })
            context.registerBean("sessions", SessionTokenService::class.java, Supplier { mock(SessionTokenService::class.java) })
            context.registerBean("passwords", PasswordEncoder::class.java, Supplier { mock(PasswordEncoder::class.java) })
            context.registerBean("clock", Clock::class.java, Supplier { Clock.systemUTC() })
            context.refresh()
            assertInstanceOf(UserServiceImpl::class.java, context.getBean(UserService::class.java))
        }
    }
}
