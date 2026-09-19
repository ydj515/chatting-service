package com.chat.persistence.redis

import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class RedisMessageAcceptance(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val redisProperties: ChatRedisProperties,
    private val keyResolver: MessageStreamKeyResolver,
) {
    fun findAccepted(roomId: Long, senderId: Long, clientMessageId: String): MessageStreamEnvelope? =
        redisTemplate.opsForValue().get(acceptanceKey(roomId, senderId, clientMessageId))?.let {
            objectMapper.readValue<MessageStreamEnvelope>(it)
        }

    fun append(envelope: MessageStreamEnvelope): MessageStreamEnvelope {
        val streams = redisProperties.streams
        val payload = redisTemplate.execute(
            ACCEPT,
            listOf(keyResolver.roomStreamKey(envelope.chatRoomId, envelope.streamShard), acceptanceKey(envelope)),
            streams.maxLen.toString(),
            if (streams.maxLenApproximate) "~" else "=",
            envelope.messageId,
            envelope.chatRoomId.toString(),
            envelope.roomSeq.toString(),
            envelope.streamShard.toString(),
            objectMapper.writeValueAsString(envelope),
        )
        return objectMapper.readValue(checkNotNull(payload) { "Redis message acceptance returned null" })
    }

    fun markPersisted(envelope: MessageStreamEnvelope) {
        redisTemplate.execute(RETAIN_PERSISTED, listOf(acceptanceKey(envelope)), envelope.messageId)
    }

    internal fun acceptanceKey(envelope: MessageStreamEnvelope): String =
        acceptanceKey(envelope.chatRoomId, envelope.senderId, envelope.clientMessageId ?: "server:${envelope.messageId}")

    private fun acceptanceKey(roomId: Long, senderId: Long, clientKey: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(clientKey.toByteArray(Charsets.UTF_8))
        return "${redisProperties.streams.roomStreamKeyPrefix}{$roomId}:accepted:$senderId:$encoded"
    }

    private companion object {
        // All keys use the same room hash tag. Keep sequence values as strings, including values above 2^53.
        val ACCEPT = DefaultRedisScript(
            """
            local existing = redis.call('GET', KEYS[2])
            if existing then return existing end
            local args = {KEYS[1]}
            if tonumber(ARGV[1]) > 0 then
                args[#args + 1] = 'MAXLEN'
                args[#args + 1] = ARGV[2]
                args[#args + 1] = ARGV[1]
            end
            local fields = {'*', 'messageId', ARGV[3], 'chatRoomId', ARGV[4],
                'roomSeq', ARGV[5], 'streamShard', ARGV[6], 'payload', ARGV[7]}
            for _, value in ipairs(fields) do args[#args + 1] = value end
            redis.call('XADD', unpack(args))
            redis.call('SET', KEYS[2], ARGV[7])
            return ARGV[7]
            """.trimIndent(),
            String::class.java,
        )

        // Pending/DLQ messages never expire. After DB commit the primary DB becomes the durable lookup;
        // keep the accepted response for another day to cover in-flight requests and ACK retries.
        val RETAIN_PERSISTED = DefaultRedisScript(
            """
            local existing = redis.call('GET', KEYS[1])
            if existing and cjson.decode(existing).messageId == ARGV[1] then
                return redis.call('EXPIRE', KEYS[1], 86400)
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
