package com.chat.persistence.redis

import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatRedisProperties
import com.chat.persistence.service.MessageStreamMetrics
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "CHAT_TEST_REDIS_PORT", matches = ".+")
class RedisMessageStreamProducerTest {
    @Test
    fun `concurrent gateways accept one envelope across different shards`() {
        Fixture().use { fixture ->
            val executor = Executors.newFixedThreadPool(8)
            try {
                val gateways = List(2) { fixture.producer() }
                val accepted = executor.invokeAll(
                    (0 until 24).map { index ->
                        Callable {
                            gateways[index % 2].append(
                                envelope().copy(
                                    messageId = "msg-$index",
                                    roomSeq = 9007199254740993L + index,
                                    sequenceNumber = 9007199254740993L + index,
                                    streamShard = index % 4,
                                ),
                            )
                        }
                    },
                ).map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, accepted.distinct().size)
                assertEquals(1L, fixture.recordCount())
                val original = accepted.first()
                assertTrue(original.roomSeq > 9007199254740992L)
                assertEquals(original, fixture.producer().append(envelope().copy(content = "retry payload")))
                assertEquals(original, fixture.producer().findAccepted(42, 7, "client-1"))
                assertEquals(-1L, fixture.redis.getExpire(fixture.acceptance.acceptanceKey(original)))
                assertEquals(1L, fixture.recordCount())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `idempotency is scoped to sender room and exact client key`() {
        Fixture().use { fixture ->
            val producer = fixture.producer()
            val messages = listOf(envelope(), envelope().copy(senderId = 8), envelope().copy(chatRoomId = 43), envelope().copy(clientMessageId = "client-2"))
            messages.forEach { assertEquals(it, producer.append(it)) }
            assertEquals(4L, fixture.recordCount())
            assertNull(producer.findAccepted(42, 7, "missing"))
        }
    }

    @Test
    fun `only confirmed persistence starts retention and stale completion cannot expire another acceptance`() {
        Fixture().use { fixture ->
            val accepted = fixture.producer().append(envelope())
            val key = fixture.acceptance.acceptanceKey(accepted)
            fixture.acceptance.markPersisted(accepted.copy(messageId = "wrong-message"))
            assertEquals(-1L, fixture.redis.getExpire(key))
            fixture.acceptance.markPersisted(accepted)
            assertTrue(fixture.redis.getExpire(key) in 86390L..86400L)
            assertEquals(accepted, fixture.producer().append(accepted.copy(messageId = "retry")))
            fixture.acceptance.markPersisted(accepted.copy(clientMessageId = "missing"))
        }
    }

    @Test
    fun `bounded streams reject without losing unread or pending records and recover after both ACKs`() {
        Fixture(1).use { f ->
            val producer = f.producer()
            val original = producer.append(envelope())
            val next = envelope().copy(messageId = "msg-2", clientMessageId = "client-2")
            assertThrows(com.chat.domain.exception.MessageAdmissionRejectedException::class.java) { producer.append(next) }
            assertNull(producer.findAccepted(42, 7, "client-2"))
            assertEquals(original, producer.append(original))
            val stream = f.resolver.roomStreamKey(42, 0)
            val ops = f.redis.opsForStream<String, String>()
            for (group in listOf("message-writer", "fanout")) {
                ops.createGroup(stream, org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"), group)
                val records = requireNotNull(
                    ops.read(
                        org.springframework.data.redis.connection.stream.Consumer.from(group, "test"),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty(),
                        org.springframework.data.redis.connection.stream.StreamOffset.create(stream, org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()),
                    ),
                )
                assertThrows(com.chat.domain.exception.MessageAdmissionRejectedException::class.java) { producer.append(next) }
                if (group == "message-writer") f.acceptance.markPersisted(original)
                ops.acknowledge(stream, group, records.single().id)
            }
            // An additional consumer also protects its unread and pending entries.
            ops.createGroup(stream, org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"), "observer")
            assertThrows(com.chat.domain.exception.MessageAdmissionRejectedException::class.java) { producer.append(next) }
            val observed = requireNotNull(
                ops.read(
                    org.springframework.data.redis.connection.stream.Consumer.from("observer", "test"),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty(),
                    org.springframework.data.redis.connection.stream.StreamOffset.create(stream, org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()),
                ),
            )
            assertThrows(com.chat.domain.exception.MessageAdmissionRejectedException::class.java) { producer.append(next) }
            ops.acknowledge(stream, "observer", observed.single().id)
            assertEquals(next, producer.append(next))
            assertEquals(1L, f.recordCount())
            assertEquals(original, producer.findAccepted(42, 7, "client-1"))
            assertTrue(f.redis.getExpire(f.acceptance.acceptanceKey(original)) > 0)
        }
    }

    @Test
    fun `unbounded mode preserves every record and writer DLQ starts retention`() {
        Fixture().use { f ->
            repeat(10) { index -> f.producer().append(envelope().copy(messageId = "msg-$index", clientMessageId = "client-$index")) }
            assertEquals(10L, f.recordCount())
            f.acceptance.markDeadLettered(requireNotNull(f.producer().findAccepted(42, 7, "client-1")))
            assertTrue(f.redis.getExpire(f.acceptance.acceptanceKey(envelope())) > 0)
        }
    }

    @Test
    fun `failed XADD leaves no acceptance and can be retried after recovery`() {
        Fixture().use { fixture ->
            val producer = fixture.producer()
            val message = envelope()
            val streamKey = fixture.resolver.roomStreamKey(42, 0)
            fixture.redis.opsForValue().set(streamKey, "wrong-type")
            assertThrows(RuntimeException::class.java) { producer.append(message) }
            assertNull(producer.findAccepted(42, 7, "client-1"))
            fixture.redis.delete(streamKey)
            assertEquals(message, producer.append(message))
            assertEquals(1L, fixture.recordCount())
        }
    }

    @Test
    fun `index failure rejects before append and success and failure metrics are recorded`() {
        Fixture().use { fixture ->
            val metrics = mock(MessageStreamMetrics::class.java)
            val producer = fixture.producer(metrics)
            fixture.redis.opsForValue().set(fixture.properties.streams.knownStreamsKey, "wrong-type")
            assertThrows(RuntimeException::class.java) { producer.append(envelope()) }
            assertNull(fixture.acceptance.findAccepted(42, 7, "client-1"))
            fixture.redis.delete(fixture.properties.streams.knownStreamsKey)
            assertEquals(envelope(), producer.append(envelope()))
            val outcomes = mockingDetails(metrics).invocations.filter { it.method.name == "recordAppend" }.map { it.arguments[1] }
            assertEquals(listOf("failure", "success"), outcomes)
        }
    }

    private class Fixture(maxLen: Long = 0, approximate: Boolean = false) : AutoCloseable {
        private val factory = LettuceConnectionFactory("127.0.0.1", System.getenv("CHAT_TEST_REDIS_PORT").toInt()).also {
            it.afterPropertiesSet()
            it.start()
        }
        val redis = StringRedisTemplate(factory)
        private val prefix = "acceptance-test:${UUID.randomUUID()}:"
        val properties = ChatRedisProperties(
            streams = ChatRedisProperties.Streams(
                roomStreamKeyPrefix = "${prefix}room:", knownStreamsKey = "${prefix}known", maxLen = maxLen, maxLenApproximate = approximate,
            ),
        )
        val resolver = MessageStreamKeyResolver(properties)
        val acceptance = RedisMessageAcceptance(redis, jacksonObjectMapper().registerModule(JavaTimeModule()), properties, resolver)

        fun producer(metrics: MessageStreamMetrics = MessageStreamMetrics.Noop): RedisMessageStreamProducer =
            RedisMessageStreamProducer(redis, properties, resolver, acceptance, metrics)

        fun recordCount(): Long = redis.opsForSet().members(properties.streams.knownStreamsKey).orEmpty().sumOf {
            redis.opsForStream<String, String>().size(it) ?: 0
        }

        override fun close() {
            try {
                redis.delete(redis.keys("$prefix*"))
            } finally {
                factory.destroy()
            }
        }
    }

    private fun envelope(): MessageStreamEnvelope = MessageStreamEnvelope(
        messageId = "msg-1", clientMessageId = "client-1", chatRoomId = 42, senderId = 7, senderName = "User 7",
        messageType = MessageType.TEXT, content = "hello", sequenceNumber = 11, roomSeq = 11,
        streamShard = 0, writeShard = 0, fanoutShard = 0, createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
    )
}
