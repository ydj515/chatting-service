package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisSystemException
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StreamOperations
import io.lettuce.core.RedisBusyException

class RedisMessageStreamConsumerTest {

    @Test
    fun `이미 보장한 consumer group은 다시 create 하지 않는다`() {
        val redisTemplate = redisTemplate()
        val streamOperations = streamOperations()
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        val consumer = RedisMessageStreamConsumer(
            redisTemplate = redisTemplate,
            objectMapper = ObjectMapper(),
            keyResolver = MessageStreamKeyResolver(ChatRedisProperties()),
        )

        consumer.ensureConsumerGroup("chat:stream:room:10:shard:0", "message-writer")
        consumer.ensureConsumerGroup("chat:stream:room:10:shard:0", "message-writer")

        verify(streamOperations, times(1))
            .createGroup("chat:stream:room:10:shard:0", ReadOffset.from("0-0"), "message-writer")
    }

    @Test
    fun `consumer group이 이미 존재하는 RedisSystemException은 성공으로 처리한다`() {
        val redisTemplate = redisTemplate()
        val streamOperations = streamOperations()
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(
            streamOperations.createGroup(
                "chat:stream:room:10:shard:0",
                ReadOffset.from("0-0"),
                "message-writer",
            )
        ).thenThrow(RedisSystemException("Error in execution", RedisBusyException("BUSYGROUP Consumer Group name already exists")))
        val consumer = RedisMessageStreamConsumer(
            redisTemplate = redisTemplate,
            objectMapper = ObjectMapper(),
            keyResolver = MessageStreamKeyResolver(ChatRedisProperties()),
        )

        consumer.ensureConsumerGroup("chat:stream:room:10:shard:0", "message-writer")
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisTemplate<String, String> {
        return mock(RedisTemplate::class.java) as RedisTemplate<String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun streamOperations(): StreamOperations<String, String, String> {
        return mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    }
}
