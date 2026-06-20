package com.chat.persistence.repository

import com.chat.persistence.service.RoomHeatPolicy
import com.chat.persistence.service.RoomPolicyRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class RoomPolicyJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) : RoomPolicyRepository {

    override fun applyAutomaticPolicy(policy: RoomHeatPolicy) {
        jdbcTemplate.update(
            UPSERT_AUTOMATIC_POLICY_SQL,
            policy.roomId,
            policy.heatLevel.name,
            policy.liveFeedMaxMessages,
            policy.liveFeedMaxAgeSeconds,
            policy.roomRateLimitPerSecond,
            policy.slowModeSeconds,
        )
    }

    private companion object {
        const val UPSERT_AUTOMATIC_POLICY_SQL = """
            INSERT INTO room_storage_configs (
                room_id,
                hot_room_policy,
                live_feed_max_messages,
                live_feed_max_age_seconds,
                room_rate_limit_per_second,
                slow_mode_seconds,
                auto_policy_enabled,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, true, now())
            ON CONFLICT (room_id) DO UPDATE SET
                hot_room_policy = EXCLUDED.hot_room_policy,
                live_feed_max_messages = EXCLUDED.live_feed_max_messages,
                live_feed_max_age_seconds = EXCLUDED.live_feed_max_age_seconds,
                room_rate_limit_per_second = EXCLUDED.room_rate_limit_per_second,
                slow_mode_seconds = EXCLUDED.slow_mode_seconds,
                updated_at = now()
            WHERE room_storage_configs.auto_policy_enabled = true
        """
    }
}
