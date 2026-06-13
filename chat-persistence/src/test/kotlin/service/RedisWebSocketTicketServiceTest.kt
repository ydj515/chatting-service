package com.chat.persistence.service

import com.chat.persistence.config.ChatAuthProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.stream.Stream

class RedisWebSocketTicketServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
    @Suppress("UNCHECKED_CAST")
    private val redisScriptMatcherPlaceholder = mock(RedisScript::class.java) as RedisScript<Long>

    @Test
    fun `ticket 발급은 원문을 Redis key와 value에 저장하지 않고 consume은 1회만 성공한다`() {
        val redis = redisTemplate()
        val service = ticketService(redis.template)

        val issued = service.issueTicket(userId = 42L, clientIp = "127.0.0.1")
            ?: throw AssertionError("ticket should be issued")

        assertTrue(issued.ticket.length >= 43)
        assertFalse(redis.storage.keys.any { it.contains(issued.ticket) })
        assertFalse(redis.storage.values.any { it.contains(issued.ticket) })
        assertFalse(redis.storage.values.any { it.contains("clientIp") })
        assertFalse(redis.storage.values.any { it.contains("127.0.0.1") })

        val consumed = service.consumeTicket(issued.ticket)
        val reused = service.consumeTicket(issued.ticket)

        assertEquals(42L, consumed?.userId)
        assertEquals(issued.expiresAt, consumed?.expiresAt)
        assertEquals(null, reused)
    }

    @Test
    fun `ticket 발급은 사용자별 rate limit을 넘으면 거부한다`() {
        val redis = redisTemplate()
        redis.scriptResults["chat:ws-ticket:rate:user:42"] = ArrayDeque(listOf(1L, 0L))
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
    fun `ticket 발급 rate limit은 Redis Lua script 결과가 거부면 실패한다`() {
        val redis = redisTemplate()
        redis.scriptResults["chat:ws-ticket:rate:user:42"] = ArrayDeque(listOf(0L))
        val properties = ChatAuthProperties(
            webSocketTicket = ChatAuthProperties.WebSocketTicket(
                ttl = Duration.ofSeconds(30),
                issueRateLimitWindow = Duration.ofMinutes(1),
                issueRateLimitPerUser = 10,
                issueRateLimitPerIp = 60,
            ),
        )
        val service = ticketService(redis.template, properties)

        assertEquals(null, service.issueTicket(userId = 42L, clientIp = "127.0.0.1"))
    }

    @Test
    fun `ticket 발급 rate limit은 Lua script에 window와 limit을 전달한다`() {
        val redis = redisTemplate()
        val properties = ChatAuthProperties(
            webSocketTicket = ChatAuthProperties.WebSocketTicket(
                ttl = Duration.ofSeconds(30),
                issueRateLimitWindow = Duration.ofMinutes(1),
                issueRateLimitPerUser = 10,
                issueRateLimitPerIp = 60,
            ),
        )
        val service = ticketService(redis.template, properties)

        service.issueTicket(userId = 42L, clientIp = null)

        org.mockito.Mockito.verify(redis.template).execute(
            anyRedisScript(),
            eq(listOf("chat:ws-ticket:rate:user:42")),
            eq("60000"),
            eq("10"),
        )
    }

    @Test
    fun `ticket 발급은 성공 outcome latency metric을 기록한다`() {
        val redis = redisTemplate()
        val meterRegistry = SimpleMeterRegistry()
        val service = ticketService(redis = redis.template, meterRegistry = meterRegistry)

        service.issueTicket(userId = 42L, clientIp = "127.0.0.1")

        assertEquals(
            1L,
            meterRegistry.find("chat.websocket.ticket.issue.latency")
                .tag("outcome", "success")
                .timer()
                ?.count(),
        )
    }

    @Test
    fun `rate limit Lua script 실패는 전용 metric을 기록하고 fail closed로 실패한다`() {
        val redis = redisTemplate()
        doAnswer {
            throw IllegalStateException("redis script failed")
        }.`when`(redis.template).execute(
            anyRedisScript(),
            eq(listOf("chat:ws-ticket:rate:user:42")),
            anyString(),
            anyString(),
        )
        val meterRegistry = SimpleMeterRegistry()
        val service = ticketService(redis = redis.template, meterRegistry = meterRegistry)

        assertEquals(null, service.issueTicket(userId = 42L, clientIp = "127.0.0.1"))
        assertEquals(
            1.0,
            meterRegistry.find("chat.websocket.ticket.rate_limit.script.failures")
                .tag("scope", "user")
                .counter()
                ?.count(),
        )
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
        meterRegistry: MeterRegistry? = null,
    ): RedisWebSocketTicketService {
        return RedisWebSocketTicketService(
            redisTemplate = redis,
            objectMapper = objectMapper,
            authProperties = properties,
            clock = clock,
            meterRegistryProvider = meterRegistry?.let { meterRegistryProvider(it) },
        )
    }

    private fun meterRegistryProvider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry {
                return meterRegistry
            }

            override fun getObject(vararg args: Any?): MeterRegistry {
                return meterRegistry
            }

            override fun getIfAvailable(): MeterRegistry {
                return meterRegistry
            }

            override fun getIfUnique(): MeterRegistry {
                return meterRegistry
            }

            override fun iterator(): MutableIterator<MeterRegistry> {
                return mutableListOf(meterRegistry).iterator()
            }

            override fun stream(): Stream<MeterRegistry> {
                return Stream.of(meterRegistry)
            }

            override fun orderedStream(): Stream<MeterRegistry> {
                return Stream.of(meterRegistry)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redisTemplate(): RedisFixture {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
        val storage = linkedMapOf<String, String>()
        val scriptResults = mutableMapOf<String, ArrayDeque<Long>>()
        `when`(template.opsForValue()).thenReturn(valueOperations)
        doAnswer { invocation ->
            val keys = invocation.arguments[1] as List<*>
            val key = keys.single() as String
            scriptResults[key]?.removeFirstOrNull() ?: 1L
        }.`when`(template).execute(anyRedisScript(), anyList<String>(), anyString(), anyString())
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
            storage = storage,
            scriptResults = scriptResults,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyRedisScript(): RedisScript<Long> {
        any<RedisScript<Long>>()
        return redisScriptMatcherPlaceholder
    }

    private data class RedisFixture(
        val template: RedisTemplate<String, String>,
        val storage: Map<String, String>,
        val scriptResults: MutableMap<String, ArrayDeque<Long>>,
    )
}
