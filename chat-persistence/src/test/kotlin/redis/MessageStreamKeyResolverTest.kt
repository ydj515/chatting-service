package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MessageStreamKeyResolverTest {

    @Test
    fun `roomStreamKey는 같은 방의 sharded stream key를 같은 Redis Cluster slot에 두기 위해 hash tag를 포함한다`() {
        val resolver = MessageStreamKeyResolver(ChatRedisProperties())

        val key = resolver.roomStreamKey(roomId = 10L, streamShard = 2)

        assertEquals("chat:stream:room:{10}:shard:2", key)
    }

    @Test
    fun `parseRoomStreamKey는 hash tagged room stream key를 파싱한다`() {
        val resolver = MessageStreamKeyResolver(ChatRedisProperties())

        val parsed = resolver.parseRoomStreamKey("chat:stream:room:{10}:shard:2")

        assertEquals(10L, parsed?.roomId)
        assertEquals(2, parsed?.streamShard)
    }

    @Test
    fun `streamReadGroupKey는 같은 방의 stream shard들을 같은 read group으로 묶는다`() {
        val resolver = MessageStreamKeyResolver(ChatRedisProperties())

        assertEquals("room:10", resolver.streamReadGroupKey("chat:stream:room:{10}:shard:0"))
        assertEquals("room:10", resolver.streamReadGroupKey("chat:stream:room:{10}:shard:1"))
        assertEquals("room:11", resolver.streamReadGroupKey("chat:stream:room:{11}:shard:0"))
    }

    @Test
    fun `streamReadGroupKey는 hash tag 없는 legacy key를 hash tagged key와 같은 group으로 묶지 않는다`() {
        val resolver = MessageStreamKeyResolver(ChatRedisProperties())

        // hash tag가 없는 legacy key는 full key hash로 slot이 결정되므로 shard마다 slot이 다르다.
        // 따라서 같은 방이라도 hash tagged group(room:10)과 섞이면 안 되고, 각 key가 독립 group이어야 한다.
        val legacyShard0 = resolver.streamReadGroupKey("chat:stream:room:10:shard:0")
        val legacyShard1 = resolver.streamReadGroupKey("chat:stream:room:10:shard:1")

        assertEquals("stream:chat:stream:room:10:shard:0", legacyShard0)
        assertEquals("stream:chat:stream:room:10:shard:1", legacyShard1)
        assertNotEquals("room:10", legacyShard0)
        assertNotEquals(legacyShard0, legacyShard1)
    }
}
