package com.chat.persistence.service

import com.chat.persistence.config.ChatAuthProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RedisWebSocketTicketServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    @Test
    fun `ticket 발급은 원문을 Redis key와 value에 저장하지 않고 consume은 1회만 성공한다`() {
        val redis = redisTemplate()
        val service = ticketService(redis.template)

        val issued = service.issueTicket(userId = 42L, clientIp = "127.0.0.1")
            ?: throw AssertionError("ticket should be issued")

        assertTrue(issued.ticket.length >= 43)
        assertFalse(redis.storage.keys.any { it.contains(issued.ticket) })
        assertFalse(redis.storage.values.any { it.contains(issued.ticket) })

        val consumed = service.consumeTicket(issued.ticket)
        val reused = service.consumeTicket(issued.ticket)

        assertEquals(42L, consumed?.userId)
        assertEquals(issued.expiresAt, consumed?.expiresAt)
        assertEquals(null, reused)
    }

    @Test
    fun `ticket 발급은 사용자별 rate limit을 넘으면 거부한다`() {
        val redis = redisTemplate()
        `when`(redis.valueOperations.increment("chat:ws-ticket:rate:user:42")).thenReturn(1L, 2L)
        `when`(redis.valueOperations.increment("chat:ws-ticket:rate:ip:127.0.0.1")).thenReturn(1L, 2L)
        val service = ticketService(
            redis = redis.template,
            properties = ChatAuthProperties(
                webSocketTicket = ChatAuthProperties.WebSocketTicket(
                    ttl = Duration.ofSeconds(30),
                    issueRateLimitPerUser = 1,
                    issueRateLimitPerIp = 100,
                ),
            ),
        )

        service.issueTicket(userId = 42L, clientIp = "127.0.0.1")

        assertEquals(null, service.issueTicket(userId = 42L, clientIp = "127.0.0.1"))
    }

    @Test
    fun `만료된 ticket과 malformed ticket은 consume에 실패한다`() {
        val redis = redisTemplate()
        val properties = ChatAuthProperties(
            webSocketTicket = ChatAuthProperties.WebSocketTicket(
                ttl = Duration.ofSeconds(30),
                issueRateLimitPerUser = 10,
                issueRateLimitPerIp = 60,
            ),
        )
        val issued = ticketService(redis.template, properties)
            .issueTicket(userId = 42L, clientIp = "127.0.0.1")
            ?: throw AssertionError("ticket should be issued")
        val laterClock = Clock.fixed(clock.instant().plus(Duration.ofSeconds(31)), ZoneOffset.UTC)
        val laterService = ticketService(redis.template, properties, laterClock)

        assertEquals(null, laterService.consumeTicket(issued.ticket))
        assertEquals(null, laterService.consumeTicket("bad"))
    }

    private fun ticketService(
        redis: RedisTemplate<String, String>,
        properties: ChatAuthProperties = ChatAuthProperties(
            webSocketTicket = ChatAuthProperties.WebSocketTicket(
                ttl = Duration.ofSeconds(30),
                issueRateLimitPerUser = 10,
                issueRateLimitPerIp = 60,
            ),
        ),
        clock: Clock = this.clock,
    ): RedisWebSocketTicketService {
        return RedisWebSocketTicketService(
            redisTemplate = redis,
            objectMapper = objectMapper,
            authProperties = properties,
            clock = clock,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisFixture {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        val storage = linkedMapOf<String, String>()
        `when`(template.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.increment("chat:ws-ticket:rate:user:42")).thenReturn(1L)
        `when`(valueOperations.increment("chat:ws-ticket:rate:ip:127.0.0.1")).thenReturn(1L)
        doAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as String
            if (storage.containsKey(key)) {
                false
            } else {
                storage[key] = value
                true
            }
        }.`when`(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration::class.java))
        doAnswer { invocation ->
            storage.remove(invocation.arguments[0] as String)
        }.`when`(valueOperations).getAndDelete(anyString())

        return RedisFixture(
            template = template,
            valueOperations = valueOperations,
            storage = storage,
        )
    }

    private data class RedisFixture(
        val template: RedisTemplate<String, String>,
        val valueOperations: ValueOperations<String, String>,
        val storage: Map<String, String>,
    )
}
