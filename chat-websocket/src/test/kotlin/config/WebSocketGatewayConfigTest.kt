package com.chat.websocket.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class WebSocketGatewayConfigTest {
    @Test
    fun `executor rejects excess drains instead of growing an unbounded queue`() {
        val executor = WebSocketGatewayConfig(ChatWebSocketGatewayProperties(outboundExecutorThreads = 1, outboundExecutorQueueCapacity = 1))
            .webSocketOutboundExecutor() as ThreadPoolExecutor
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                running.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            executor.execute { }
            assertEquals(1, executor.queue.size)
            assertThrows(RejectedExecutionException::class.java) { executor.execute { } }
            assertEquals(1, executor.queue.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
