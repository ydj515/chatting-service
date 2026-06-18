package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.MessageSequenceProperties
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service

@Service
class MessageSequenceService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
    private val sequenceProperties: MessageSequenceProperties,
) {
    fun getNextSequence(chatRoomId: Long): Long {
        val key = "${redisProperties.sequenceKeyPrefix}:$chatRoomId"
        val nextSequence = redisTemplate.opsForValue().increment(key, 1L)
            ?: error("Failed to allocate sequence for room $chatRoomId")
        if (nextSequence == 1L) {
            redisTemplate.expire(key, sequenceProperties.ttl)
        }

        return nextSequence
    }
}
