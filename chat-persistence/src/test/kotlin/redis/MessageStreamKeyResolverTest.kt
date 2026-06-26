package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import org.junit.jupiter.api.Assertions.assertEquals
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
}
