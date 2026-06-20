package com.chat.persistence.service

import com.chat.persistence.config.ChatRoomPolicyProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import kotlin.math.ceil

interface RoomTrafficStatsService {
    fun recordAccepted(roomId: Long)
    fun activeRoomIds(): Set<Long>
    fun snapshot(roomId: Long): RoomTrafficSnapshot

    object Noop : RoomTrafficStatsService {
        override fun recordAccepted(roomId: Long) = Unit
        override fun activeRoomIds(): Set<Long> = emptySet()
        override fun snapshot(roomId: Long): RoomTrafficSnapshot {
            return RoomTrafficSnapshot(
                roomId = roomId,
                roomMessagesPerSecond = 0,
                roomMessagesP95PerSecond = 0,
            )
        }
    }
}

@Service
class RedisRoomTrafficStatsService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val properties: ChatRoomPolicyProperties,
    private val clock: Clock,
) : RoomTrafficStatsService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun recordAccepted(roomId: Long) {
        try {
            val epochSecond = nowEpochSecond()
            val key = trafficCounterKey(roomId, epochSecond)
            val count = redisTemplate.opsForValue().increment(key, 1L)
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(properties.trafficCounterTtlSeconds))
                redisTemplate.opsForZSet().add(properties.activeRoomsKey, roomId.toString(), epochSecond.toDouble())
            }
        } catch (e: Exception) {
            logger.warn("Failed to record room traffic for roomId={}", roomId, e)
        }
    }

    override fun activeRoomIds(): Set<Long> {
        val epochSecond = nowEpochSecond()
        val cutoffEpochSecond = epochSecond - properties.trafficWindowSeconds
        try {
            redisTemplate.opsForZSet().removeRangeByScore(
                properties.activeRoomsKey,
                0.0,
                cutoffEpochSecond.toDouble(),
            )
        } catch (e: Exception) {
            logger.warn("Failed to cleanup active room index", e)
        }

        return redisTemplate.opsForZSet()
            .rangeByScore(
                properties.activeRoomsKey,
                cutoffEpochSecond.toDouble(),
                epochSecond.toDouble(),
            )
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    override fun snapshot(roomId: Long): RoomTrafficSnapshot {
        val epochSecond = nowEpochSecond()
        val keys = trafficWindow(epochSecond).map { trafficCounterKey(roomId, it) }
        val counts = redisTemplate.opsForValue()
            .multiGet(keys)
            .orEmpty()
            .map { it?.toLongOrNull() ?: 0L }

        return RoomTrafficSnapshot(
            roomId = roomId,
            roomMessagesPerSecond = counts.lastOrNull() ?: 0L,
            roomMessagesP95PerSecond = percentile95(counts),
        )
    }

    private fun trafficWindow(epochSecond: Long): LongRange {
        val size = properties.trafficWindowSeconds.coerceAtLeast(1)
        return (epochSecond - size + 1)..epochSecond
    }

    private fun percentile95(counts: List<Long>): Long {
        if (counts.isEmpty()) {
            return 0
        }
        val sorted = counts.sorted()
        val index = (ceil(sorted.size * P95) - 1).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun trafficCounterKey(roomId: Long, epochSecond: Long): String {
        return "${properties.trafficKeyPrefix}{$roomId}:sec:$epochSecond"
    }

    private fun nowEpochSecond(): Long = clock.instant().epochSecond

    private companion object {
        const val P95 = 0.95
    }
}
