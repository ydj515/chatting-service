package com.chat.persistence.service

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.MessageSequenceProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class MessageSequenceServiceTest {

    @Test
    fun `시퀀스 키 prefix와 TTL은 설정값을 사용한다`() {
        val redisTemplate = redisTemplate()
        val valueOperations = valueOperations()
        val ttl = Duration.ofHours(2)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.increment("custom:sequence:1", 1000L)).thenReturn(1000L)

        val service = MessageSequenceService(
            redisTemplate = redisTemplate,
            redisProperties = ChatRedisProperties(sequenceKeyPrefix = "custom:sequence"),
            sequenceProperties = MessageSequenceProperties(ttl = ttl),
        )

        val sequence = service.getNextSequence(chatRoomId = 1)

        assertEquals(1L, sequence)
        verify(redisTemplate).expire("custom:sequence:1", ttl)
    }

    @Test
    fun `이미 존재하는 시퀀스 키에는 TTL을 다시 설정하지 않는다`() {
        val redisTemplate = redisTemplate()
        val valueOperations = valueOperations()
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.increment("chat:sequence:1", 1000L)).thenReturn(2000L)

        val service = MessageSequenceService(
            redisTemplate = redisTemplate,
            redisProperties = ChatRedisProperties(),
            sequenceProperties = MessageSequenceProperties(),
        )

        val sequence = service.getNextSequence(chatRoomId = 1)

        assertEquals(1001L, sequence)
        verify(redisTemplate, never()).expire("chat:sequence:1", Duration.ofHours(24))
    }

    @Test
    fun `시퀀스는 Redis INCRBY로 block을 할당하고 local block에서 순서대로 반환한다`() {
        val redisTemplate = redisTemplate()
        val valueOperations = valueOperations()
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.increment("chat:sequence:9", 1000L)).thenReturn(1000L)

        val service = MessageSequenceService(
            redisTemplate = redisTemplate,
            redisProperties = ChatRedisProperties(),
            sequenceProperties = MessageSequenceProperties(),
        )

        assertEquals(1L, service.getNextSequence(chatRoomId = 9))
        assertEquals(2L, service.getNextSequence(chatRoomId = 9))

        verify(valueOperations).increment("chat:sequence:9", 1000L)
        verify(redisTemplate).expire("chat:sequence:9", Duration.ofHours(24))
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisTemplate<String, String> =
        mock(RedisTemplate::class.java) as RedisTemplate<String, String>

    @Suppress("UNCHECKED_CAST")
    private fun valueOperations(): ValueOperations<String, String> =
        mock(ValueOperations::class.java) as ValueOperations<String, String>
}
