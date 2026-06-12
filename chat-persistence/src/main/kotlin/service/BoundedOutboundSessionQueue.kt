package com.chat.persistence.service

import java.util.ArrayDeque
import java.util.concurrent.Executor

class BoundedOutboundSessionQueue(
    maxPendingMessages: Int,
    private val executor: Executor,
    private val sender: (String) -> Unit,
    private val onOverflow: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val maxPendingMessages = maxPendingMessages.coerceAtLeast(1)
    private val lock = Any()
    private val pendingMessages = ArrayDeque<String>()
    private var sending = false
    private var closed = false

    fun enqueue(message: String): Boolean {
        var shouldStartDrain = false
        var overflowed = false

        synchronized(lock) {
            if (closed) {
                return false
            }

            if (pendingMessages.size >= maxPendingMessages) {
                pendingMessages.clear()
                closed = true
                overflowed = true
            } else {
                pendingMessages.addLast(message)
                if (!sending) {
                    sending = true
                    shouldStartDrain = true
                }
            }
        }

        if (overflowed) {
            onOverflow()
            return false
        }

        if (shouldStartDrain) {
            executor.execute { drain() }
        }

        return true
    }

    fun pendingSize(): Int {
        return synchronized(lock) {
            pendingMessages.size
        }
    }

    fun isClosed(): Boolean {
        return synchronized(lock) {
            closed
        }
    }

    private fun drain() {
        while (true) {
            val next = nextMessage() ?: return
            try {
                sender(next)
            } catch (throwable: Throwable) {
                close()
                onFailure(throwable)
                return
            }
        }
    }

    private fun nextMessage(): String? {
        return synchronized(lock) {
            if (closed) {
                sending = false
                null
            } else {
                val next = pendingMessages.pollFirst()
                if (next == null) {
                    sending = false
                }
                next
            }
        }
    }

    fun close() {
        synchronized(lock) {
            pendingMessages.clear()
            closed = true
            sending = false
        }
    }
}
