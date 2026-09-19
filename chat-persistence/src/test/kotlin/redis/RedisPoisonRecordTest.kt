package com.chat.persistence.redis

import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.config.RedisConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.LocalDateTime
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "CHAT_TEST_REDIS_PORT", matches = ".+")
class RedisPoisonRecordTest {
    @Test
    fun `malformed and missing payloads move to raw DLQ without losing valid neighbors`() {
        Fixture().use { f ->
            val badId = checkNotNull(f.streams.add(f.key, mapOf("payload" to "{invalid", "extra" to "preserved")))
            f.streams.add(f.key, mapOf("other" to "missing payload"))
            f.streams.add(f.key, mapOf("payload" to "null"))
            f.addValid()
            val records = f.read()
            assertEquals(listOf("valid"), records.map { it.envelope.messageId })
            val dlq = f.streams.range(f.dlq, Range.unbounded<String>()).orEmpty()
            assertEquals(3, dlq.size)
            val raw = dlq.single { it.value["sourceRecordId"] == badId.value }.value
            assertTrue(raw.getValue("rawFields").contains("preserved"))
            assertTrue(raw.getValue("rawFields").contains("{invalid"))
            assertEquals(1L, f.streams.pending(f.key, "writer").totalPendingMessages)
            assertTrue(f.claim().all { it.envelope.messageId == "valid" })
            assertEquals(3L, f.streams.size(f.dlq))
        }
    }

    @Test
    fun `DLQ failure keeps only poison pending and recovery quarantines it before acknowledgement`() {
        Fixture().use { f ->
            f.streams.add(f.key, mapOf("payload" to "{invalid"))
            f.addValid()
            f.redis.opsForValue().set(f.dlq, "wrong-type")
            val valid = f.read().single()
            assertEquals("valid", valid.envelope.messageId)
            f.consumer.acknowledge(f.key, "writer", valid.recordId)
            assertEquals(1L, f.streams.pending(f.key, "writer").totalPendingMessages)
            assertTrue(f.claim().isEmpty())
            assertEquals(1L, f.streams.pending(f.key, "writer").totalPendingMessages)
            f.redis.delete(f.dlq)
            assertTrue(f.claim().isEmpty())
            assertEquals(0L, f.streams.pending(f.key, "writer").totalPendingMessages)
            assertEquals(1L, f.streams.size(f.dlq))
        }
    }

    private class Fixture : AutoCloseable {
        private val factory = LettuceConnectionFactory("127.0.0.1", System.getenv("CHAT_TEST_REDIS_PORT").toInt()).also {
            it.afterPropertiesSet()
            it.start()
        }
        val redis = StringRedisTemplate(factory)
        val streams: StreamOperations<String, String, String> = redis.opsForStream<String, String>()
        private val prefix = "poison-test:${UUID.randomUUID()}:"
        private val properties = ChatRedisProperties(streams = ChatRedisProperties.Streams(roomStreamKeyPrefix = "${prefix}room:", knownStreamsKey = "${prefix}known", deadLetterStreamKeyPrefix = "${prefix}dlq:"))
        private val resolver = MessageStreamKeyResolver(properties)
        private val mapper = RedisConfig().distributedObjectMapper()
        val key = resolver.roomStreamKey(42, 0)
        val dlq = resolver.deadLetterStreamKey("writer")
        val consumer = RedisMessageStreamConsumer(redis, mapper, resolver)

        fun addValid() {
            val envelope = MessageStreamEnvelope("valid", "client", 42, 7, "Synthetic", MessageType.TEXT, "valid", 1, 1, 0, 0, 0, LocalDateTime.now())
            streams.add(key, mapOf("payload" to mapper.writeValueAsString(envelope)))
        }

        fun read(): List<MessageStreamRecord> {
            consumer.ensureConsumerGroup(key, "writer")
            return consumer.readNew("writer", "worker", setOf(key), 100)
        }

        fun claim(): List<MessageStreamRecord> = consumer.claimPending("writer", "worker", setOf(key), 100, 0)

        override fun close() {
            try {
                redis.delete(redis.keys("$prefix*"))
            } finally {
                factory.destroy()
            }
        }
    }
}
