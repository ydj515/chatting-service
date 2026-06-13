package com.chat.persistence.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalWriteShardResolverTest {

    @Test
    fun `resolve는 messageId hash를 room currentShardCount로 나눈 값을 반환한다`() {
        val resolver = CanonicalWriteShardResolver(
            roomStorageConfigReader = FakeRoomStorageConfigReader(shardCounts = mapOf(10L to 16)),
        )

        val shard = resolver.resolve(roomId = 10L, messageId = "msg-1")

        assertEquals(Math.floorMod("msg-1".hashCode(), 16), shard)
    }

    @Test
    fun `resolve는 shard count가 1보다 작으면 1개 shard로 fallback한다`() {
        val resolver = CanonicalWriteShardResolver(
            roomStorageConfigReader = FakeRoomStorageConfigReader(shardCounts = mapOf(10L to 0)),
        )

        val shard = resolver.resolve(roomId = 10L, messageId = "msg-1")

        assertEquals(0, shard)
    }

    private class FakeRoomStorageConfigReader(
        private val shardCounts: Map<Long, Int>,
    ) : RoomStorageConfigReader {
        override fun currentShardCount(roomId: Long): Int {
            return shardCounts.getValue(roomId)
        }
    }
}
