package com.chat.persistence.service

import com.chat.core.auth.SessionTokenDigests
import com.chat.core.auth.service.WebSocketTicketSessionPolicy
import com.chat.core.service.SessionTokenService
import com.chat.persistence.config.ChatAuthProperties
import com.chat.persistence.config.RedisConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "CHAT_TEST_REDIS_PORT", matches = ".+")
class WebSocketTicketRevocationTest {
    @Test
    fun `out of order retry cannot move the user revocation cutoff backwards`() {
        Fixture().use { f ->
            val latest = f.clock.instant()
            f.revocations.revokeUserTokens(7, latest)
            f.revocations.revokeUserTokens(7, latest.minusSeconds(60))
            assertEquals(latest, f.revocations.userRevokedAt(7))
        }
    }

    @Test
    fun `individual logout invalidates all bound tickets without affecting another session`() {
        Fixture().use { f ->
            val first = f.tokens.issueToken(7).token
            val other = f.tokens.issueToken(7).token
            val revokedTickets = List(2) { checkNotNull(f.tickets.issueTicket(7, null, first)) }
            val valid = checkNotNull(f.tickets.issueTicket(7, null, other))
            val rawValues = f.redis.keys("${f.prefix}ticket:*").map { f.redis.opsForValue().get(it).orEmpty() }
            assertTrue(rawValues.none { it.contains(first) || it.contains(other) })
            f.tokens.revokeToken(first)
            revokedTickets.forEach { assertNull(f.tickets.consumeTicket(it.ticket)) }
            assertEquals(7L, f.tickets.consumeTicket(valid.ticket)?.userId)
            assertNull(f.tickets.consumeTicket(valid.ticket))
            assertNull(f.tickets.issueTicket(7, null, first))
        }
    }

    @Test
    fun `user revocation rejects outstanding tickets and later sessions can issue fresh tickets`() {
        Fixture().use { f ->
            val token = f.tokens.issueToken(7).token
            val old = checkNotNull(f.tickets.issueTicket(7, null, token))
            f.clock.advance(2)
            f.tokens.revokeUserTokens(7)
            assertNull(f.tickets.consumeTicket(old.ticket))
            f.clock.advance(2)
            val fresh = f.tokens.issueToken(7).token
            val ticket = checkNotNull(f.tickets.issueTicket(7, null, fresh))
            assertEquals(7L, f.tickets.consumeTicket(ticket.ticket)?.userId)
        }
    }

    @Test
    fun `revocation racing ticket issue is checked against session issue time`() {
        Fixture().use { f ->
            val token = f.tokens.issueToken(7).token
            val authenticated = checkNotNull(f.tokens.authenticate(token))
            val delayedAuthentication = mock(SessionTokenService::class.java)
            `when`(delayedAuthentication.authenticate(token)).thenAnswer {
                f.clock.advance(2)
                f.tokens.revokeUserTokens(7)
                f.clock.advance(2)
                authenticated
            }
            val ticket = checkNotNull(f.ticketService(delayedAuthentication).issueTicket(7, null, token))
            assertNull(f.tickets.consumeTicket(ticket.ticket))
        }
    }

    @Test
    fun `ticket expiry cannot outlive the parent session and mismatched user is rejected`() {
        Fixture().use { f ->
            val token = f.tokens.issueToken(7).token
            f.clock.advance(3590)
            assertNull(f.tickets.issueTicket(8, null, token))
            val ticket = checkNotNull(f.tickets.issueTicket(7, null, token))
            assertEquals(f.tokens.authenticate(token)?.expiresAt, ticket.expiresAt)
            f.clock.advance(11)
            assertNull(f.tickets.consumeTicket(ticket.ticket))
        }
    }

    @Test
    fun `legacy unbound tickets and revocation lookup failure are rejected`() {
        Fixture().use { f ->
            val token = f.tokens.issueToken(7).token
            val ticket = checkNotNull(f.tickets.issueTicket(7, null, token))
            val ticketKey = "${f.prefix}ticket:${SessionTokenDigests.sha256(ticket.ticket)}"
            f.redis.opsForValue().set(ticketKey, """{"userId":7,"expiresAtEpochSecond":${f.clock.instant().plusSeconds(30).epochSecond}}""")
            assertNull(f.tickets.consumeTicket(ticket.ticket))
            val next = checkNotNull(f.tickets.issueTicket(7, null, token))
            f.redis.opsForList().rightPush("${f.prefix}revoked:token:${SessionTokenDigests.sha256(token)}", "wrong-type")
            assertNull(f.tickets.consumeTicket(next.ticket))
        }
    }

    private class Fixture : AutoCloseable {
        private val factory = LettuceConnectionFactory("127.0.0.1", System.getenv("CHAT_TEST_REDIS_PORT").toInt()).also {
            it.afterPropertiesSet()
            it.start()
        }
        val redis = StringRedisTemplate(factory)
        val prefix = "ticket-revoke-test:${UUID.randomUUID()}:"
        val clock = MutableClock(Instant.parse("2026-09-20T00:00:00Z"))
        private val properties = ChatAuthProperties(
            session = ChatAuthProperties.Session(secret = "synthetic-ticket-test-signing-key-0123456789", ttl = Duration.ofHours(1), revocationKeyPrefix = "${prefix}revoked:"),
            webSocketTicket = ChatAuthProperties.WebSocketTicket(keyPrefix = "${prefix}ticket:", rateLimitKeyPrefix = "${prefix}rate:"),
        )
        val revocations = RedisSessionTokenRevocationStore(redis, properties, clock)
        val tokens = HmacSessionTokenService(properties, clock, revocations)
        val tickets = ticketService(tokens)

        fun ticketService(authentication: SessionTokenService): RedisWebSocketTicketService =
            RedisWebSocketTicketService(redis, RedisConfig().distributedObjectMapper(), properties, clock, WebSocketTicketSessionPolicy(authentication, revocations, clock))

        override fun close() {
            try {
                redis.delete(redis.keys("$prefix*"))
            } finally {
                factory.destroy()
            }
        }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
