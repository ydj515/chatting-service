package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service

@Service
class MessageSequenceService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
) {
    fun getNextSequence(chatRoomId: Long): Long {
        val key = "${redisProperties.sequenceKeyPrefix}:$chatRoomId"
        val value: String? = redisTemplate.execute(ALLOCATE_SEQUENCE, listOf(key))
        return checkNotNull(value) { "Failed to allocate sequence for room $chatRoomId" }.toLong()
    }

    internal companion object {
        // Keep existing values and remove old TTLs in the same atomic operation as INCR.
        val ALLOCATE_SEQUENCE = DefaultRedisScript(
            "redis.call('INCR', KEYS[1]); redis.call('PERSIST', KEYS[1]); return redis.call('GET', KEYS[1])",
            String::class.java,
        )
    }
}
