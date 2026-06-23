package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.PendingMessagesSummary
import org.springframework.data.redis.connection.stream.StreamInfo
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StreamOperations

class RedisStreamLagReaderTest {

    @Test
    fun `known stream group의 lag와 pending summary를 읽는다`() {
        val redisTemplate = redisTemplate()
        val setOperations = setOperations()
        val streamOperations = streamOperations()
        val streamKey = "chat:stream:room:10:shard:2"
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(setOperations.members("chat:stream:rooms")).thenReturn(setOf(streamKey))
        `when`(streamOperations.groups(streamKey)).thenReturn(xInfoGroups("message-writer", lag = 9L, pending = 3L))
        `when`(streamOperations.pending(streamKey, "message-writer")).thenReturn(pendingSummary("message-writer", 5L))
        val reader = SpringRedisStreamLagReader(
            redisTemplate = redisTemplate,
            keyResolver = MessageStreamKeyResolver(ChatRedisProperties()),
        )

        val snapshots = reader.read(consumerGroups = setOf("message-writer", "fanout"))

        assertEquals(
            listOf(
                RedisStreamGroupLagSnapshot(
                    streamShard = 2,
                    consumerGroup = "message-writer",
                    lag = 9L,
                    pending = 5L,
                ),
            ),
            snapshots,
        )
        verify(streamOperations, times(1)).pending(streamKey, "message-writer")
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisTemplate<String, String> {
        return mock(RedisTemplate::class.java) as RedisTemplate<String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun setOperations(): SetOperations<String, String> {
        return mock(SetOperations::class.java) as SetOperations<String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun streamOperations(): StreamOperations<String, String, String> {
        return mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    }

    private fun xInfoGroups(
        consumerGroup: String,
        lag: Long,
        pending: Long,
    ): StreamInfo.XInfoGroups {
        val raw: MutableList<Any> = mutableListOf(
            mutableListOf<Any>(
                "name",
                consumerGroup,
                "consumers",
                1L,
                "pending",
                pending,
                "last-delivered-id",
                "0-0",
                "entries-read",
                1L,
                "lag",
                lag,
            ),
        )
        return StreamInfo.XInfoGroups.fromList(raw)
    }

    private fun pendingSummary(consumerGroup: String, pending: Long): PendingMessagesSummary {
        return PendingMessagesSummary(
            consumerGroup,
            pending,
            Range.closed("0-0", "1-0"),
            emptyMap(),
        )
    }
}
