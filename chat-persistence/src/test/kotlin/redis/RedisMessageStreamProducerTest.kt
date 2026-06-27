package com.chat.persistence.redis

import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.chat.persistence.service.MessageStreamMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StreamOperations
import java.time.LocalDateTime
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

class RedisMessageStreamProducerTest {

    @Test
    fun `메시지 envelope를 room stream shard key에 append한다`() {
        val redisTemplate = redisTemplate()
        val streamOperations = streamOperations()
        val setOperations = setOperations()
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(streamOperations.add(eq("chat:stream:room:{42}:shard:3"), anyStringMap()))
            .thenReturn(RecordId.of("1749790000000-0"))

        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
        val producer = RedisMessageStreamProducer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisProperties = unboundedRedisProperties(),
            keyResolver = MessageStreamKeyResolver(unboundedRedisProperties()),
        )

        val recordId = producer.append(
            MessageStreamEnvelope(
                messageId = "msg-1",
                clientMessageId = "client-1",
                chatRoomId = 42L,
                senderId = 7L,
                senderName = "User 7",
                messageType = MessageType.TEXT,
                content = "hello",
                sequenceNumber = 11L,
                roomSeq = 11L,
                streamShard = 3,
                writeShard = 4,
                fanoutShard = 5,
                createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            )
        )

        assertEquals("1749790000000-0", recordId)

        val fieldsCaptor = stringMapCaptor()
        verify(streamOperations).add(eq("chat:stream:room:{42}:shard:3"), captureStringMap(fieldsCaptor))
        val fields = fieldsCaptor.value
        assertEquals("msg-1", fields["messageId"])
        assertEquals("42", fields["chatRoomId"])
        assertEquals("11", fields["roomSeq"])
        assertEquals("3", fields["streamShard"])
        assertTrue(fields["payload"]!!.contains("\"messageId\":\"msg-1\""))
        assertTrue(fields["payload"]!!.contains("\"clientMessageId\":\"client-1\""))
        verify(setOperations).add("chat:stream:rooms", "chat:stream:room:{42}:shard:3")
    }

    @Test
    fun `이미 등록한 stream key는 known stream set에 다시 쓰지 않는다`() {
        val redisTemplate = redisTemplate()
        val streamOperations = streamOperations()
        val setOperations = setOperations()
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(streamOperations.add(eq("chat:stream:room:{42}:shard:3"), anyStringMap()))
            .thenReturn(RecordId.of("1749790000000-0"), RecordId.of("1749790000000-1"))

        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
        val producer = RedisMessageStreamProducer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisProperties = unboundedRedisProperties(),
            keyResolver = MessageStreamKeyResolver(unboundedRedisProperties()),
        )
        val envelope = MessageStreamEnvelope(
            messageId = "msg-1",
            clientMessageId = "client-1",
            chatRoomId = 42L,
            senderId = 7L,
            senderName = "User 7",
            messageType = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 11L,
            roomSeq = 11L,
            streamShard = 3,
            writeShard = 4,
            fanoutShard = 5,
            createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
        )

        producer.append(envelope)
        producer.append(envelope.copy(messageId = "msg-2", roomSeq = 12L, sequenceNumber = 12L))

        verify(setOperations, times(1)).add("chat:stream:rooms", "chat:stream:room:{42}:shard:3")
        verify(streamOperations, times(2)).add(eq("chat:stream:room:{42}:shard:3"), anyStringMap())
    }

    @Test
    fun `stream append latency metric은 shard와 outcome만 tag로 기록한다`() {
        val redisTemplate = redisTemplate()
        val streamOperations = streamOperations()
        val setOperations = setOperations()
        val meterRegistry = SimpleMeterRegistry()
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(streamOperations.add(eq("chat:stream:room:{42}:shard:3"), anyStringMap()))
            .thenReturn(RecordId.of("1749790000000-0"))

        val producer = RedisMessageStreamProducer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper(),
            redisProperties = unboundedRedisProperties(),
            keyResolver = MessageStreamKeyResolver(unboundedRedisProperties()),
            messageStreamMetrics = MessageStreamMetrics(meterRegistryProvider(meterRegistry)),
        )

        producer.append(envelope())

        val timer = meterRegistry.find("chat.redis.stream.append.latency")
            .tag("stream_shard", "3")
            .tag("outcome", "success")
            .timer()

        assertEquals(1, timer?.count())
    }

    @Test
    fun `maxLen이 양수이면 XADD MAXLEN 옵션으로 bounded append한다`() {
        val redisTemplate = redisTemplate()
        val setOperations = setOperations()
        val redisConnection = mock(RedisConnection::class.java)
        val streamCommands = mock(RedisStreamCommands::class.java)
        val recordCaptor = byteMapRecordCaptor()
        val optionsCaptor = ArgumentCaptor.forClass(RedisStreamCommands.XAddOptions::class.java)
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(redisConnection.streamCommands()).thenReturn(streamCommands)
        `when`(streamCommands.xAdd(captureByteMapRecord(recordCaptor), optionsCaptor.capture()))
            .thenReturn(RecordId.of("1749790000000-0"))
        `when`(redisTemplate.execute(anyRecordIdRedisCallback())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val callback = invocation.arguments[0] as RedisCallback<RecordId>
            callback.doInRedis(redisConnection)
        }
        val redisProperties = ChatRedisProperties(
            streams = ChatRedisProperties.Streams(
                maxLen = 128,
                maxLenApproximate = true,
            ),
        )
        val producer = RedisMessageStreamProducer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper(),
            redisProperties = redisProperties,
            keyResolver = MessageStreamKeyResolver(redisProperties),
        )

        val recordId = producer.append(envelope())

        assertEquals("1749790000000-0", recordId)
        assertEquals(128L, optionsCaptor.value.maxlen ?: -1L)
        assertTrue(optionsCaptor.value.isApproximateTrimming)
        assertArrayEquals(bytes("chat:stream:room:{42}:shard:3"), recordCaptor.value.stream)

        val fields = recordCaptor.value.value.entries.associate { entry ->
            String(entry.key, StandardCharsets.UTF_8) to String(entry.value, StandardCharsets.UTF_8)
        }
        assertEquals("msg-1", fields["messageId"])
        assertEquals("42", fields["chatRoomId"])
        assertEquals("11", fields["roomSeq"])
        assertEquals("3", fields["streamShard"])
        assertTrue(fields["payload"]!!.contains("\"messageId\":\"msg-1\""))
        verify(setOperations).add("chat:stream:rooms", "chat:stream:room:{42}:shard:3")
    }

    @Test
    fun `maxLen approximate가 꺼져 있으면 exact trim 옵션으로 bounded append한다`() {
        val redisTemplate = redisTemplate()
        val setOperations = setOperations()
        val redisConnection = mock(RedisConnection::class.java)
        val streamCommands = mock(RedisStreamCommands::class.java)
        val optionsCaptor = ArgumentCaptor.forClass(RedisStreamCommands.XAddOptions::class.java)
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(redisConnection.streamCommands()).thenReturn(streamCommands)
        `when`(streamCommands.xAdd(anyByteMapRecord(), optionsCaptor.capture()))
            .thenReturn(RecordId.of("1749790000000-0"))
        `when`(redisTemplate.execute(anyRecordIdRedisCallback())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val callback = invocation.arguments[0] as RedisCallback<RecordId>
            callback.doInRedis(redisConnection)
        }
        val redisProperties = ChatRedisProperties(
            streams = ChatRedisProperties.Streams(
                maxLen = 64,
                maxLenApproximate = false,
            ),
        )
        val producer = RedisMessageStreamProducer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper(),
            redisProperties = redisProperties,
            keyResolver = MessageStreamKeyResolver(redisProperties),
        )

        producer.append(envelope())

        assertEquals(64L, optionsCaptor.value.maxlen ?: -1L)
        assertFalse(optionsCaptor.value.isApproximateTrimming)
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisTemplate<String, String> {
        return mock(RedisTemplate::class.java) as RedisTemplate<String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun streamOperations(): StreamOperations<String, String, String> {
        return mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun setOperations(): SetOperations<String, String> {
        return mock(SetOperations::class.java) as SetOperations<String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringMapCaptor(): ArgumentCaptor<Map<String, String>> {
        return ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, String>>
    }

    private fun anyStringMap(): Map<String, String> {
        org.mockito.ArgumentMatchers.anyMap<String, String>()
        return uninitialized()
    }

    private fun captureStringMap(captor: ArgumentCaptor<Map<String, String>>): Map<String, String> {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun byteMapRecordCaptor(): ArgumentCaptor<MapRecord<ByteArray, ByteArray, ByteArray>> {
        return ArgumentCaptor.forClass(MapRecord::class.java) as ArgumentCaptor<MapRecord<ByteArray, ByteArray, ByteArray>>
    }

    private fun anyByteMapRecord(): MapRecord<ByteArray, ByteArray, ByteArray> {
        org.mockito.ArgumentMatchers.any<MapRecord<ByteArray, ByteArray, ByteArray>>()
        return uninitialized()
    }

    private fun captureByteMapRecord(
        captor: ArgumentCaptor<MapRecord<ByteArray, ByteArray, ByteArray>>,
    ): MapRecord<ByteArray, ByteArray, ByteArray> {
        captor.capture()
        return uninitialized()
    }

    private fun anyRecordIdRedisCallback(): RedisCallback<RecordId> {
        org.mockito.ArgumentMatchers.any<RedisCallback<RecordId>>()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T

    private fun unboundedRedisProperties(): ChatRedisProperties {
        return ChatRedisProperties(
            streams = ChatRedisProperties.Streams(maxLen = 0),
        )
    }

    private fun bytes(value: String): ByteArray {
        return value.toByteArray(StandardCharsets.UTF_8)
    }

    private fun objectMapper(): ObjectMapper {
        return ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
    }

    private fun envelope(): MessageStreamEnvelope {
        return MessageStreamEnvelope(
            messageId = "msg-1",
            clientMessageId = "client-1",
            chatRoomId = 42L,
            senderId = 7L,
            senderName = "User 7",
            messageType = MessageType.TEXT,
            content = "hello",
            sequenceNumber = 11L,
            roomSeq = 11L,
            streamShard = 3,
            writeShard = 4,
            fanoutShard = 5,
            createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
        )
    }

    private fun meterRegistryProvider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry = meterRegistry
            override fun getObject(vararg args: Any?): MeterRegistry = meterRegistry
            override fun getIfAvailable(): MeterRegistry = meterRegistry
            override fun getIfUnique(): MeterRegistry = meterRegistry
            override fun iterator(): MutableIterator<MeterRegistry> = mutableListOf(meterRegistry).iterator()
            override fun stream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
            override fun orderedStream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
        }
    }
}
