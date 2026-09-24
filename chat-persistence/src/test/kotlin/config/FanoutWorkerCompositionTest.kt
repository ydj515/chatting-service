package com.chat.persistence.config

import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamKeyResolver
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.service.FanoutOwnerLeaseService
import com.chat.persistence.service.HotRoomFanoutWorker
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.UnsatisfiedDependencyException
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class FanoutWorkerCompositionTest {
    @Test
    fun `fanout cannot start without the configured stream key resolver`() {
        context().use { context ->
            context.beanFactory.registerSingleton("lease", FanoutOwnerLeaseService.Noop)
            assertThrows(UnsatisfiedDependencyException::class.java) { context.refresh() }
        }
    }

    @Test
    fun `fanout cannot start without an owner lease implementation`() {
        context().use { context ->
            context.beanFactory.registerSingleton("keys", MessageStreamKeyResolver(ChatRedisProperties()))
            assertThrows(UnsatisfiedDependencyException::class.java) { context.refresh() }
        }
    }

    @Test
    fun `fanout starts with both required collaborators`() {
        context().use { context ->
            context.beanFactory.registerSingleton("keys", MessageStreamKeyResolver(ChatRedisProperties()))
            context.beanFactory.registerSingleton("lease", FanoutOwnerLeaseService.Noop)
            context.refresh()
            assertNotNull(context.getBean(HotRoomFanoutWorker::class.java))
        }
    }

    private fun context() = AnnotationConfigApplicationContext().apply {
        beanFactory.registerSingleton("consumer", mock(MessageStreamConsumer::class.java))
        beanFactory.registerSingleton("broker", mock(RedisMessageBroker::class.java))
        beanFactory.registerSingleton("properties", ChatWorkerProperties())
        register(HotRoomFanoutWorker::class.java)
    }
}
