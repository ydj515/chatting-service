package com.chat.persistence.redis

import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatRedisProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StreamOperations
import java.time.LocalDateTime

class RedisMessageStreamProducerTest {

    @Test
    fun `메시지 envelope를 room stream shard key에 append한다`() {
        val redisTemplate = redisTemplate()
        val streamOperations = streamOperations()
        `when`(redisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(streamOperations.add(eq("chat:stream:room:42:shard:3"), anyStringMap()))
            .thenReturn(RecordId.of("1749790000000-0"))

        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
        val producer = RedisMessageStreamProducer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            redisProperties = ChatRedisProperties(),
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
        verify(streamOperations).add(eq("chat:stream:room:42:shard:3"), captureStringMap(fieldsCaptor))
        val fields = fieldsCaptor.value
        assertEquals("msg-1", fields["messageId"])
        assertEquals("42", fields["chatRoomId"])
        assertEquals("11", fields["roomSeq"])
        assertEquals("3", fields["streamShard"])
        assertTrue(fields["payload"]!!.contains("\"messageId\":\"msg-1\""))
        assertTrue(fields["payload"]!!.contains("\"clientMessageId\":\"client-1\""))
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
    private fun <T> uninitialized(): T = null as T
}
