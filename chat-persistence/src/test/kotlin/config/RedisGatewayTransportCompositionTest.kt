package com.chat.persistence.config

import com.chat.persistence.redis.RedisGatewayRoomTransport
import com.chat.persistence.redis.RedisMessageBroker
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate

class RedisGatewayTransportCompositionTest {
    @Test
    fun `gateway uses the configured template when the Boot string template also exists`() {
        @Suppress("UNCHECKED_CAST")
        val configured = mock(RedisTemplate::class.java) as RedisTemplate<String, String>

        @Suppress("UNCHECKED_CAST")
        val sets = mock(SetOperations::class.java) as SetOperations<String, String>
        val bootTemplate = mock(StringRedisTemplate::class.java)
        val broker = mock(RedisMessageBroker::class.java)
        `when`(configured.opsForSet()).thenReturn(sets)
        `when`(broker.getServerId()).thenReturn("gateway-1")
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("redisTemplate", configured)
            context.beanFactory.registerSingleton("stringRedisTemplate", bootTemplate)
            context.beanFactory.registerSingleton("properties", ChatRedisProperties())
            context.beanFactory.registerSingleton("broker", broker)
            context.register(RedisGatewayRoomTransport::class.java)
            context.refresh()
            assertTrue(context.getBean(RedisGatewayRoomTransport::class.java).synchronizeServerRoom(10, true))
            verify(sets).add("chat:server:rooms:gateway-1", "10")
            verifyNoInteractions(bootTemplate)
        }
    }
}
