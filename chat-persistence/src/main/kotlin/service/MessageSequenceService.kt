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

        // INCR 명령어를 사용하여 원자적인 증가
        val sequence = redisTemplate.opsForValue().increment(key) ?: 1L
        if (sequence == 1L) {
            redisTemplate.expire(key, sequenceProperties.ttl)
        }
        return sequence
    }
}
