package com.chat.persistence.config

import com.chat.persistence.service.RoomAdmissionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
