package com.chat.persistence.config

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.persistence.repository.ModerationRuleRecord
import com.chat.persistence.service.RoomAdmissionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CacheConfigTest {

    @Test
    fun `Redis cache serializer는 RoomAdmissionPolicy 타입을 보존한다`() {
        val serializer = CacheConfig.redisCacheValueSerializer()
        val policy = RoomAdmissionPolicy(
            roomRateLimitPerSecond = 1000,
            userRateLimitPerSecond = 10,
            slowModeSeconds = 1,
            moderatorPriority = false,
        )

        val restored = serializer.deserialize(serializer.serialize(policy))

        assertTrue(restored is RoomAdmissionPolicy)
        assertEquals(policy, restored)
    }

    @Test
    fun `Redis cache serializer는 ModerationRuleRecord list 타입을 보존한다`() {
        val serializer = CacheConfig.redisCacheValueSerializer()
        val rules = listOf(
            ModerationRuleRecord(
                id = 1L,
                scopeType = ModerationScopeType.GLOBAL,
                roomId = null,
                pattern = "blocked",
                matchType = ModerationMatchType.CONTAINS,
                action = ModerationAction.REJECT,
                reason = "blocked phrase",
                enabled = true,
                createdBy = "admin-local",
                createdAt = Instant.parse("2026-06-26T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
            ),
        )

        val restored = serializer.deserialize(serializer.serialize(rules))

        assertTrue(restored is List<*>)
        assertEquals(rules, restored)
    }
}
