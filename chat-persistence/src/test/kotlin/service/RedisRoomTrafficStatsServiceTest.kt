package com.chat.persistence.service

import com.chat.persistence.config.ChatRoomPolicyProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RedisRoomTrafficStatsServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-18T00:01:00Z"), ZoneOffset.UTC)

    @Test
    fun `accepted message 기록은 room second counter와 active room index를 갱신한다`() {
        val redis = redisTemplate()
        val service = RedisRoomTrafficStatsService(
            redisTemplate = redis.template,
            properties = ChatRoomPolicyProperties(),
            clock = clock,
        )

        service.recordAccepted(roomId = 10L)

        verify(redis.valueOperations).increment("chat:room-traffic:{10}:sec:1781740860", 1L)
        verify(redis.template).expire("chat:room-traffic:{10}:sec:1781740860", Duration.ofSeconds(120))
        verify(redis.zSetOperations).add("chat:room-traffic:active-rooms", "10", 1781740860.0)
        verify(redis.zSetOperations).removeRangeByScore("chat:room-traffic:active-rooms", 0.0, 1781740800.0)
    }

    @Test
    fun `snapshot은 최근 window의 current 초당 메시지와 p95를 계산한다`() {
        val redis = redisTemplate()
        val values = MutableList(60) { "10" }
        values[56] = "100"
        values[57] = "100"
        values[58] = "100"
        values[59] = "200"
        `when`(redis.valueOperations.multiGet(anyListOfStrings())).thenReturn(values)
        val service = RedisRoomTrafficStatsService(
            redisTemplate = redis.template,
            properties = ChatRoomPolicyProperties(),
            clock = clock,
        )

        val snapshot = service.snapshot(roomId = 10L)

        assertEquals(10L, snapshot.roomId)
        assertEquals(200, snapshot.roomMessagesPerSecond)
        assertEquals(100, snapshot.roomMessagesP95PerSecond)
    }

    @Test
    fun `active rooms는 최근 window 안에 기록된 room id만 Long으로 반환한다`() {
        val redis = redisTemplate()
        `when`(
            redis.zSetOperations.rangeByScore(
                "chat:room-traffic:active-rooms",
                1781740800.0,
                1781740860.0,
            ),
        ).thenReturn(setOf("10", "bad", "11"))
        val service = RedisRoomTrafficStatsService(
            redisTemplate = redis.template,
            properties = ChatRoomPolicyProperties(),
            clock = clock,
        )

        assertEquals(setOf(10L, 11L), service.activeRoomIds())
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisFixture {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        val zSetOperations = mock(ZSetOperations::class.java) as ZSetOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOperations)
        `when`(template.opsForZSet()).thenReturn(zSetOperations)
        return RedisFixture(template, valueOperations, zSetOperations)
    }

    private fun anyListOfStrings(): List<String> {
        any<List<String>>()
        return emptyList()
    }

    private data class RedisFixture(
        val template: RedisTemplate<String, String>,
        val valueOperations: ValueOperations<String, String>,
        val zSetOperations: ZSetOperations<String, String>,
    )
}
