package com.chat.websocket.service

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
    private val priorityMessages = ArrayDeque<String>()
    private val normalMessages = ArrayDeque<String>()
    private var sending = false
    private var closed = false

    fun enqueue(message: String, priority: Boolean = false): Boolean {
        var shouldStartDrain = false
        var overflowed = false

        synchronized(lock) {
            if (closed) {
                return false
            }

            if (priority) {
                if (pendingSizeLocked() >= maxPendingMessages) {
                    if (normalMessages.isNotEmpty()) {
                        normalMessages.removeLast()
                    } else {
                        priorityMessages.removeLast()
                    }
                }
                priorityMessages.addLast(message)
                if (!sending) {
                    sending = true
                    shouldStartDrain = true
                }
            } else if (pendingSizeLocked() >= maxPendingMessages) {
                priorityMessages.clear()
                normalMessages.clear()
                closed = true
                overflowed = true
            } else {
                normalMessages.addLast(message)
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

    fun pendingSize(): Int =
        synchronized(lock) {
            pendingSizeLocked()
        }

    fun isClosed(): Boolean =
        synchronized(lock) {
            closed
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

    private fun nextMessage(): String? =
        synchronized(lock) {
            if (closed) {
                sending = false
                null
            } else {
                val next = priorityMessages.pollFirst() ?: normalMessages.pollFirst()
                if (next == null) {
                    sending = false
                }
                next
            }
        }

    fun close() {
        synchronized(lock) {
            priorityMessages.clear()
            normalMessages.clear()
            closed = true
            sending = false
        }
    }

    private fun pendingSizeLocked(): Int = priorityMessages.size + normalMessages.size
}
