package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.service.MessageStreamMetrics
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageStreamProducer(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
    private val keyResolver: MessageStreamKeyResolver,
    private val acceptance: RedisMessageAcceptance,
    private val messageStreamMetrics: MessageStreamMetrics = MessageStreamMetrics.Noop,
) : MessageStreamProducer {
    private val knownStreamsCache = ConcurrentHashMap<String, Boolean>()

    override fun findAccepted(roomId: Long, senderId: Long, clientMessageId: String): MessageStreamEnvelope? =
        acceptance.findAccepted(roomId, senderId, clientMessageId)

    override fun append(envelope: MessageStreamEnvelope): MessageStreamEnvelope {
        val startedAtNanos = System.nanoTime()
        var outcome = "failure"
        return try {
            // Register before accepting so an index failure cannot strand an accepted record.
            registerStream(keyResolver.roomStreamKey(envelope.chatRoomId, envelope.streamShard))
            val accepted = acceptance.append(envelope)
            // A retry may target a different shard; repair discovery for the original stream too.
            registerStream(keyResolver.roomStreamKey(accepted.chatRoomId, accepted.streamShard))
            outcome = "success"
            accepted
        } finally {
            messageStreamMetrics.recordAppend(envelope.streamShard, outcome, System.nanoTime() - startedAtNanos)
        }
    }

    private fun registerStream(streamKey: String) {
        knownStreamsCache.computeIfAbsent(streamKey) {
            redisTemplate.opsForSet().add(redisProperties.streams.knownStreamsKey, streamKey)
            true
        }
    }
}
