package com.chat.persistence.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RedisListenerLifecycleTest {
    @Test
    fun `Spring closes the bounded listener executor`() {
        val context = GenericApplicationContext()
        context.registerBean("redisListenerExecutor", ThreadPoolTaskExecutor::class.java, java.util.function.Supplier { RedisConfig().redisListenerExecutor() })
        context.refresh()
        val executor = context.getBean(ThreadPoolTaskExecutor::class.java)
        try {
            val completed = CountDownLatch(1)
            executor.execute { completed.countDown() }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(32, executor.maxPoolSize)
            assertEquals(1024, executor.threadPoolExecutor.queue.remainingCapacity())
        } finally {
            context.close()
        }
        assertTrue(executor.threadPoolExecutor.isShutdown)
    }
}
