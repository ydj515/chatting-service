package com.chat.persistence.service

import com.chat.domain.dto.AuthenticatedWebSocketTicket
import com.chat.domain.dto.WebSocketTicketResponse
import com.chat.domain.service.WebSocketTicketService
import com.chat.persistence.config.ChatAuthProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

@Service
class RedisWebSocketTicketService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
    private val ticketSessionPolicy: WebSocketTicketSessionPolicy,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : WebSocketTicketService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val rateLimitScript: RedisScript<Long> = DefaultRedisScript(
        RATE_LIMIT_SCRIPT,
        Long::class.javaObjectType,
    )

    override fun issueTicket(userId: Long, clientIp: String?, sessionToken: String): WebSocketTicketResponse? {
        val startedAtNanos = System.nanoTime()
        var outcome = ISSUE_OUTCOME_FAILURE
        return try {
            val session = ticketSessionPolicy.authenticate(userId, sessionToken)
                ?: return null
            if (
                !withinRateLimit(
                    key = rateLimitUserKey(userId),
                    limit = authProperties.webSocketTicket.issueRateLimitPerUser,
                    scope = RATE_LIMIT_SCOPE_USER,
                )
            ) {
                record("issue.rate_limited_user")
                outcome = ISSUE_OUTCOME_RATE_LIMITED_USER
                return null
            }
            val normalizedClientIp = clientIp?.takeIf { it.isNotBlank() }
            if (
                normalizedClientIp != null &&
                !withinRateLimit(
                    key = rateLimitIpKey(normalizedClientIp),
                    limit = authProperties.webSocketTicket.issueRateLimitPerIp,
                    scope = RATE_LIMIT_SCOPE_IP,
                )
            ) {
                record("issue.rate_limited_ip")
                outcome = ISSUE_OUTCOME_RATE_LIMITED_IP
                return null
            }

            val issuedAt = clock.instant()
            val sessionExpiresAt = session.expiresAt.toInstant(ZoneOffset.UTC)
            val expiresAt = minOf(issuedAt.plus(authProperties.webSocketTicket.ttl), sessionExpiresAt)
            if (!expiresAt.isAfter(issuedAt)) return null
            repeat(MAX_TICKET_GENERATION_ATTEMPTS) {
                val ticket = newTicket()
                val storedTicket = StoredWebSocketTicket(
                    userId = userId,
                    issuedAtEpochSecond = issuedAt.epochSecond,
                    expiresAtEpochSecond = expiresAt.epochSecond,
                    sessionTokenDigest = SessionTokenDigests.sha256(sessionToken),
                    sessionIssuedAtEpochSecond = session.issuedAt?.toEpochSecond(ZoneOffset.UTC),
                    sessionExpiresAtEpochSecond = sessionExpiresAt.epochSecond,
                )
                val stored = redisTemplate.opsForValue().setIfAbsent(
                    ticketKey(ticket),
                    objectMapper.writeValueAsString(storedTicket),
                    Duration.between(issuedAt, expiresAt),
                ) == true
                if (stored) {
                    record("issue.success")
                    outcome = ISSUE_OUTCOME_SUCCESS
                    return WebSocketTicketResponse(
                        ticket = ticket,
                        expiresAt = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                    )
                }
            }
            record("issue.collision")
            outcome = ISSUE_OUTCOME_COLLISION
            null
        } catch (e: Exception) {
            record("issue.failure")
            outcome = ISSUE_OUTCOME_FAILURE
            logger.warn("Failed to issue WebSocket ticket", e)
            null
        } finally {
            recordIssueLatency(outcome, System.nanoTime() - startedAtNanos)
        }
    }

    override fun consumeTicket(ticket: String): AuthenticatedWebSocketTicket? {
        val normalizedTicket = ticket.trim().takeIf { it.length in MIN_TICKET_LENGTH..MAX_TICKET_LENGTH }
            ?: return null.also { record("consume.malformed") }
        return try {
            val value = redisTemplate.opsForValue().getAndDelete(ticketKey(normalizedTicket))
                ?: return null.also { record("consume.miss") }
            val storedTicket = objectMapper.readValue(value, StoredWebSocketTicket::class.java)
            val expiresAt = Instant.ofEpochSecond(storedTicket.expiresAtEpochSecond)
            if (!expiresAt.isAfter(clock.instant())) {
                record("consume.expired")
                return null
            }

            if (!sessionStillValid(storedTicket)) {
                record("consume.revoked")
                return null
            }
            record("consume.success")
            AuthenticatedWebSocketTicket(
                userId = storedTicket.userId,
                expiresAt = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                sessionTokenDigest = storedTicket.sessionTokenDigest,
                parentSession = com.chat.domain.dto.AuthenticatedSession(
                    userId = storedTicket.userId,
                    expiresAt = LocalDateTime.ofInstant(Instant.ofEpochSecond(checkNotNull(storedTicket.sessionExpiresAtEpochSecond)), ZoneOffset.UTC),
                    issuedAt = storedTicket.sessionIssuedAtEpochSecond?.let { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC) },
                ),
            )
        } catch (e: Exception) {
            record("consume.failure")
            logger.warn("Failed to consume WebSocket ticket", e)
            null
        }
    }

    private fun sessionStillValid(ticket: StoredWebSocketTicket): Boolean =
        ticketSessionPolicy.isValid(ticket.userId, ticket.sessionTokenDigest, ticket.sessionIssuedAtEpochSecond, ticket.sessionExpiresAtEpochSecond)

    private fun record(event: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.websocket.ticket.events")
                .tag("event", event)
                .register(registry)
                .increment()
        }
    }

    private fun recordIssueLatency(outcome: String, durationNanos: Long) {
        meterRegistryProvider?.ifAvailable { registry ->
            Timer.builder("chat.websocket.ticket.issue.latency")
                .tag("outcome", outcome)
                .register(registry)
                .record(maxOf(durationNanos, 0L), TimeUnit.NANOSECONDS)
        }
    }

    private fun recordRateLimitScriptFailure(scope: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.websocket.ticket.rate_limit.script.failures")
                .tag("scope", scope)
                .register(registry)
                .increment()
        }
    }

    private fun withinRateLimit(key: String, limit: Long, scope: String): Boolean {
        if (limit <= 0) {
            return false
        }

        val windowMillis = authProperties.webSocketTicket.issueRateLimitWindow.toMillis()
        if (windowMillis <= 0) {
            return false
        }

        val allowed = try {
            redisTemplate.execute(
                rateLimitScript,
                listOf(key),
                windowMillis.toString(),
                limit.toString(),
            )
        } catch (e: Exception) {
            recordRateLimitScriptFailure(scope)
            throw e
        }

        return when (allowed) {
            RATE_LIMIT_ALLOWED -> true
            RATE_LIMIT_REJECTED -> false
            else -> {
                recordRateLimitScriptFailure(scope)
                false
            }
        }
    }

    private fun newTicket(): String {
        val bytes = ByteArray(TICKET_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    private fun ticketKey(ticket: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ticket.toByteArray(StandardCharsets.UTF_8))
        return "${authProperties.webSocketTicket.keyPrefix}${encoder.encodeToString(digest)}"
    }

    private fun rateLimitUserKey(userId: Long): String =
        "${authProperties.webSocketTicket.rateLimitKeyPrefix}user:$userId"

    private fun rateLimitIpKey(clientIp: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clientIp.toByteArray(StandardCharsets.UTF_8))
        return "${authProperties.webSocketTicket.rateLimitKeyPrefix}ip:${encoder.encodeToString(digest)}"
    }

    private data class StoredWebSocketTicket(
        val userId: Long = 0,
        val issuedAtEpochSecond: Long = 0,
        val expiresAtEpochSecond: Long = 0,
        val sessionTokenDigest: String? = null,
        val sessionIssuedAtEpochSecond: Long? = null,
        val sessionExpiresAtEpochSecond: Long? = null,
    )

    private companion object {
        const val TICKET_BYTE_LENGTH = 32
        const val MIN_TICKET_LENGTH = 32
        const val MAX_TICKET_LENGTH = 512
        const val MAX_TICKET_GENERATION_ATTEMPTS = 3
        const val RATE_LIMIT_ALLOWED = 1L
        const val RATE_LIMIT_REJECTED = 0L
        const val RATE_LIMIT_SCOPE_USER = "user"
        const val RATE_LIMIT_SCOPE_IP = "ip"
        const val ISSUE_OUTCOME_SUCCESS = "success"
        const val ISSUE_OUTCOME_FAILURE = "failure"
        const val ISSUE_OUTCOME_COLLISION = "collision"
        const val ISSUE_OUTCOME_RATE_LIMITED_USER = "rate_limited_user"
        const val ISSUE_OUTCOME_RATE_LIMITED_IP = "rate_limited_ip"
        const val RATE_LIMIT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])

            if current == 1 or ttl == -1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end

            if current <= tonumber(ARGV[2]) then
              return 1
            end

            return 0
        """
    }
}
