package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.RedisConfig
import com.chat.protocol.websocket.ErrorMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ScheduledFuture
import java.util.function.Supplier

class RedisMessageBrokerLifecycleTest {
    @Test
    fun `periodic cleanup expires successive generations and shutdown cancels it`() {
        val scheduler = mock(TaskScheduler::class.java)
        val future = mock(ScheduledFuture::class.java)
        val clock = mock(Clock::class.java)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        var now = start
        `when`(clock.instant()).thenAnswer { now }
        `when`(clock.millis()).thenAnswer { now.toEpochMilli() }
        lateinit var cleanup: Runnable
        doAnswer { invocation ->
            cleanup = invocation.getArgument(0)
            assertEquals(start.plusSeconds(30), invocation.getArgument<Instant>(1))
            assertEquals(Duration.ofMinutes(1), invocation.getArgument<Duration>(2))
            future
        }.`when`(scheduler).scheduleWithFixedDelay(any(Runnable::class.java), any(Instant::class.java), any(Duration::class.java))
        @Suppress("UNCHECKED_CAST")
        val redis = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val mapper = RedisConfig().distributedObjectMapper()
        val broker = RedisMessageBroker(redis, mock(RedisMessageListenerContainer::class.java), mapper, ChatRedisProperties(), scheduler, clock)
        var deliveries = 0
        broker.setLocalMessageHandler { _, _ -> deliveries++ }
        val payload = RedisMessageBroker.DistributedMessage(
            "message-1", "remote-server", 10, null, LocalDateTime.of(2026, 1, 1, 0, 0), ErrorMessage("test", chatRoomId = 10),
        )
        val message = DefaultMessage("chat.room.10".toByteArray(), mapper.writeValueAsBytes(payload))
        broker.initialize()
        try {
            broker.onMessage(message, null)
            now = start.plusSeconds(59)
            cleanup.run()
            broker.onMessage(message, null)
            assertEquals(1, deliveries)
            now = start.plusSeconds(60)
            cleanup.run()
            broker.onMessage(message, null)
            assertEquals(2, deliveries)
            now = start.plusSeconds(120)
            cleanup.run()
            broker.onMessage(message, null)
            assertEquals(3, deliveries)
        } finally {
            broker.cleanup()
        }
        verify(future).cancel(false)
    }

    @Test
    fun `Spring closes the cleanup executor with the context`() {
        val scheduler = RedisConfig().redisBrokerCleanupScheduler()
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean("redisBrokerCleanupScheduler", ThreadPoolTaskScheduler::class.java, Supplier { scheduler })
            context.refresh()
        }
        assertTrue(scheduler.scheduledThreadPoolExecutor.isShutdown)
    }

    @Test
    fun `invalid cleanup timing fails during configuration binding`() {
        assertThrows(IllegalArgumentException::class.java) { ChatRedisProperties.Broker(cleanupInitialDelay = Duration.ofMillis(-1)) }
        assertThrows(IllegalArgumentException::class.java) { ChatRedisProperties.Broker(processedMessageTtl = Duration.ZERO) }
    }
}
