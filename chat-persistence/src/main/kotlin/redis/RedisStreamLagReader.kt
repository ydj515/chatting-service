package com.chat.persistence.redis

import org.springframework.data.redis.connection.stream.StreamInfo
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service

data class RedisStreamGroupLagSnapshot(
    val streamShard: Int?,
    val consumerGroup: String,
    val lag: Long?,
    val pending: Long,
)

interface RedisStreamLagReader {
    fun read(consumerGroups: Set<String>): List<RedisStreamGroupLagSnapshot>
}

@Service
class SpringRedisStreamLagReader(
    private val redisTemplate: RedisTemplate<String, String>,
    private val keyResolver: MessageStreamKeyResolver,
) : RedisStreamLagReader {

    override fun read(consumerGroups: Set<String>): List<RedisStreamGroupLagSnapshot> {
        if (consumerGroups.isEmpty()) {
            return emptyList()
        }

        val streamOperations = redisTemplate.opsForStream<String, String>()
        val streamKeys = redisTemplate.opsForSet().members(keyResolver.knownStreamsKey()) ?: emptySet()
        return streamKeys.flatMap { streamKey ->
            val streamShard = keyResolver.parseRoomStreamKey(streamKey)?.streamShard
            val groupsByName = readGroups(streamOperations, streamKey).associateBy { group -> group.groupName() }
            consumerGroups.mapNotNull { consumerGroup ->
                val groupInfo = groupsByName[consumerGroup] ?: return@mapNotNull null
                RedisStreamGroupLagSnapshot(
                    streamShard = streamShard,
                    consumerGroup = consumerGroup,
                    lag = groupInfo.lagOrNull(),
                    pending = groupInfo.pendingCount(),
                )
            }
        }
    }

    private fun readGroups(
        streamOperations: org.springframework.data.redis.core.StreamOperations<String, String, String>,
        streamKey: String,
    ): List<StreamInfo.XInfoGroup> {
        return runCatching {
            streamOperations.groups(streamKey)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun StreamInfo.XInfoGroup.lagOrNull(): Long? {
        val rawLag = raw["lag"] ?: return null
        return when (rawLag) {
            is Number -> rawLag.toLong()
            else -> rawLag.toString().toLongOrNull()
        }
    }
}
