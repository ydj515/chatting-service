package com.chat.persistence.repository

import com.chat.persistence.service.RoomHeatLevel
import com.chat.persistence.service.RoomHeatPolicy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.cache.annotation.Caching
import org.springframework.jdbc.core.JdbcTemplate

class RoomPolicyJdbcRepositoryTest {

    @Test
    fun `automatic heat policy는 primary jdbcTemplate로 room_storage_configs를 upsert한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomPolicyJdbcRepository(jdbcTemplate)

        repository.applyAutomaticPolicy(
            RoomHeatPolicy(
                roomId = 10L,
                heatLevel = RoomHeatLevel.VERY_HOT,
                liveFeedMaxMessages = 500,
                liveFeedMaxAgeSeconds = 30,
                roomRateLimitPerSecond = 5000,
                slowModeSeconds = 1,
                writeShardCount = 64,
                fanoutShardCount = 64,
            ),
        )

        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(jdbcTemplate).update(
            sqlCaptor.capture(),
            eq(10L),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(5000),
            eq(1),
            eq(64),
            eq(64),
        )
        assertTrue(sqlCaptor.value.contains("INSERT INTO room_storage_configs"))
        assertTrue(sqlCaptor.value.contains("ON CONFLICT (room_id) DO UPDATE"))
        assertTrue(sqlCaptor.value.contains("hot_room_policy = EXCLUDED.hot_room_policy"))
        assertTrue(sqlCaptor.value.contains("live_feed_max_messages = EXCLUDED.live_feed_max_messages"))
        assertTrue(sqlCaptor.value.contains("current_shard_count"))
        assertTrue(sqlCaptor.value.contains("fanout_shard_count"))
        assertTrue(sqlCaptor.value.contains("GREATEST(room_storage_configs.current_shard_count, EXCLUDED.current_shard_count)"))
        assertTrue(sqlCaptor.value.contains("GREATEST(room_storage_configs.fanout_shard_count, EXCLUDED.fanout_shard_count)"))
        assertTrue(sqlCaptor.value.contains("auto_policy_enabled"))
        assertTrue(sqlCaptor.value.contains("WHERE room_storage_configs.auto_policy_enabled = true"))
    }

    @Test
    fun `automatic heat policy 적용 후 admission과 shard config cache를 evict한다`() {
        val method = RoomPolicyJdbcRepository::class.java.getMethod("applyAutomaticPolicy", RoomHeatPolicy::class.java)

        val caching = method.getAnnotation(Caching::class.java)
        val evictedCaches = caching.evict.flatMap { it.cacheNames.toList() + it.value.toList() }

        assertTrue(evictedCaches.contains("roomAdmissionPolicies"))
        assertTrue(evictedCaches.contains("roomShardConfigs"))
    }
}
