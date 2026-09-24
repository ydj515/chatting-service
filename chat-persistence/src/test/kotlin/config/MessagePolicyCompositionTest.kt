package com.chat.persistence.config

import com.chat.core.message.port.MessageAdmissionPolicyService
import com.chat.core.message.port.MessageModerationPolicyService
import com.chat.core.message.port.UserSanctionPolicyService
import com.chat.core.message.service.MessageAdmissionService
import com.chat.core.message.service.MessageModerationService
import com.chat.core.message.service.UserSanctionService
import com.chat.persistence.repository.MessagePolicyReadAdapter
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.service.MicrometerMessagePolicyMetrics
import com.chat.persistence.service.RedisMessageRateLimiter
import com.chat.persistence.service.RoomAdmissionPolicy
import com.chat.persistence.service.RoomAdmissionPolicyReader
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.redis.core.RedisTemplate
import java.time.Clock

class MessagePolicyCompositionTest {
    @Test
    fun `each message policy port resolves to its core use case with real adapters`() {
        val admission = mock(RoomAdmissionPolicyReader::class.java)
        `when`(admission.admissionPolicy(10)).thenReturn(RoomAdmissionPolicy())
        @Suppress("UNCHECKED_CAST")
        val redis = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("sanctions", mock(UserSanctionJdbcRepository::class.java))
            context.beanFactory.registerSingleton("rules", mock(ModerationRuleJdbcRepository::class.java))
            context.beanFactory.registerSingleton("admission", admission)
            context.beanFactory.registerSingleton("redis", redis)
            context.beanFactory.registerSingleton("clock", Clock.systemUTC())
            context.beanFactory.registerSingleton("properties", ChatRedisProperties())
            context.register(
                MessagePolicyReadAdapter::class.java, RedisMessageRateLimiter::class.java, MicrometerMessagePolicyMetrics::class.java,
                MessageAdmissionService::class.java, MessageModerationService::class.java, UserSanctionService::class.java,
            )
            context.refresh()
            assertSame(context.getBean(MessageAdmissionService::class.java), context.getBean(MessageAdmissionPolicyService::class.java))
            assertSame(context.getBean(MessageModerationService::class.java), context.getBean(MessageModerationPolicyService::class.java))
            assertSame(context.getBean(UserSanctionService::class.java), context.getBean(UserSanctionPolicyService::class.java))
            context.getBean(MessageAdmissionPolicyService::class.java).requireAllowed(10, 7)
            verifyNoInteractions(redis)
        }
    }
}
