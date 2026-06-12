package com.chat.persistence.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedOutboundSessionQueueTest {

    @Test
    fun `pending message가 상한을 넘으면 overflow callback을 실행하고 큐를 닫는다`() {
        val firstSendStarted = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val overflowCount = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()
        val queue = BoundedOutboundSessionQueue(
            maxPendingMessages = 1,
            executor = executor,
            sender = {
                firstSendStarted.countDown()
                releaseFirstSend.await(3, TimeUnit.SECONDS)
            },
            onOverflow = {
                overflowCount.incrementAndGet()
            },
            onFailure = {},
        )

        try {
            assertTrue(queue.enqueue("first"))
            assertTrue(firstSendStarted.await(3, TimeUnit.SECONDS))
            assertTrue(queue.enqueue("second"))

            val accepted = queue.enqueue("third")

            assertEquals(false, accepted)
            assertEquals(1, overflowCount.get())
            assertTrue(queue.isClosed())
            assertTrue(queue.pendingSize() <= 1)
        } finally {
            releaseFirstSend.countDown()
            executor.shutdownNow()
        }
    }
}
