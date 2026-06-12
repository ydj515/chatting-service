package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.MessageSequenceProperties
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class MessageSequenceService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
    private val sequenceProperties: MessageSequenceProperties,
) {
    private val sequenceBlocks = ConcurrentHashMap<Long, SequenceBlock>()

    fun getNextSequence(chatRoomId: Long): Long {
        var nextSequence: Long? = null
        sequenceBlocks.compute(chatRoomId) { _, currentBlock ->
            val block = if (currentBlock?.hasNext() == true) {
                currentBlock
            } else {
                allocateBlock(chatRoomId)
            }
            nextSequence = block.next()
            block
        }

        return nextSequence ?: error("Failed to allocate sequence for room $chatRoomId")
    }

    private fun allocateBlock(chatRoomId: Long): SequenceBlock {
        val key = "${redisProperties.sequenceKeyPrefix}:$chatRoomId"
        val blockSize = sequenceProperties.blockSize.coerceAtLeast(1)
        val upperBound = redisTemplate.opsForValue().increment(key, blockSize.toLong()) ?: blockSize.toLong()
        if (upperBound == blockSize.toLong()) {
            redisTemplate.expire(key, sequenceProperties.ttl)
        }

        val firstSequence = upperBound - blockSize + 1
        return SequenceBlock(
            nextSequence = firstSequence,
            endInclusive = upperBound,
        )
    }

    private data class SequenceBlock(
        private var nextSequence: Long,
        private val endInclusive: Long,
    ) {
        fun hasNext(): Boolean = nextSequence <= endInclusive

        fun next(): Long {
            val current = nextSequence
            nextSequence++
            return current
        }
    }
}
