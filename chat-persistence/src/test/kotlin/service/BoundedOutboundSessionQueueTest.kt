package com.chat.persistence.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class BoundedOutboundSessionQueueTest {

    @Test
    fun `priority enqueue makes room by dropping newest normal payload and drains first`() {
        val scheduled = mutableListOf<Runnable>()
        val sent = mutableListOf<String>()
        var overflowCount = 0
        val queue = BoundedOutboundSessionQueue(
            maxPendingMessages = 2,
            executor = Executor { scheduled.add(it) },
            sender = { sent.add(it) },
            onOverflow = { overflowCount += 1 },
            onFailure = { throw it },
        )

        assertTrue(queue.enqueue("normal-1"))
        assertTrue(queue.enqueue("normal-2"))
        assertTrue(queue.enqueue("priority", priority = true))

        scheduled.single().run()

        assertEquals(0, overflowCount)
        assertEquals(listOf("priority", "normal-1"), sent)
    }

    @Test
    fun `priority enqueue preserves FIFO order within priority messages`() {
        val scheduled = mutableListOf<Runnable>()
        val sent = mutableListOf<String>()
        val queue = BoundedOutboundSessionQueue(
            maxPendingMessages = 4,
            executor = Executor { scheduled.add(it) },
            sender = { sent.add(it) },
            onOverflow = {},
            onFailure = { throw it },
        )

        assertTrue(queue.enqueue("normal-1"))
        assertTrue(queue.enqueue("priority-1", priority = true))
        assertTrue(queue.enqueue("priority-2", priority = true))
        assertTrue(queue.enqueue("normal-2"))

        scheduled.single().run()

        assertEquals(listOf("priority-1", "priority-2", "normal-1", "normal-2"), sent)
    }
}
