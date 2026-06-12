package com.chat.persistence.service

import com.chat.domain.dto.AuthenticatedWebSocketTicket
import com.chat.domain.dto.WebSocketTicketResponse
import com.chat.domain.service.WebSocketTicketService
import com.chat.persistence.config.ChatAuthProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

@Service
class RedisWebSocketTicketService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : WebSocketTicketService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun issueTicket(userId: Long, clientIp: String?): WebSocketTicketResponse? {
        return try {
            if (!withinRateLimit(rateLimitUserKey(userId), authProperties.webSocketTicket.issueRateLimitPerUser)) {
                record("issue.rate_limited_user")
                return null
            }
            val normalizedClientIp = clientIp?.takeIf { it.isNotBlank() }
            if (
                normalizedClientIp != null &&
                !withinRateLimit(rateLimitIpKey(normalizedClientIp), authProperties.webSocketTicket.issueRateLimitPerIp)
            ) {
                record("issue.rate_limited_ip")
                return null
            }

            val issuedAt = clock.instant()
            val expiresAt = issuedAt.plus(authProperties.webSocketTicket.ttl)
            repeat(MAX_TICKET_GENERATION_ATTEMPTS) {
                val ticket = newTicket()
                val storedTicket = StoredWebSocketTicket(
                    userId = userId,
                    issuedAtEpochSecond = issuedAt.epochSecond,
                    expiresAtEpochSecond = expiresAt.epochSecond,
                    clientIp = normalizedClientIp,
                )
                val stored = redisTemplate.opsForValue().setIfAbsent(
                    ticketKey(ticket),
                    objectMapper.writeValueAsString(storedTicket),
                    authProperties.webSocketTicket.ttl,
                ) == true
                if (stored) {
                    record("issue.success")
                    return WebSocketTicketResponse(
                        ticket = ticket,
                        expiresAt = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                    )
                }
            }
            record("issue.collision")
            null
        } catch (e: Exception) {
            record("issue.failure")
            logger.warn("Failed to issue WebSocket ticket", e)
            null
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

            record("consume.success")
            AuthenticatedWebSocketTicket(
                userId = storedTicket.userId,
                expiresAt = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
            )
        } catch (e: Exception) {
            record("consume.failure")
            logger.warn("Failed to consume WebSocket ticket", e)
            null
        }
    }

    private fun record(event: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.websocket.ticket.events")
                .tag("event", event)
                .register(registry)
                .increment()
        }
    }

    private fun withinRateLimit(key: String, limit: Long): Boolean {
        if (limit <= 0) {
            return false
        }

        val count = redisTemplate.opsForValue().increment(key) ?: return false
        if (count == 1L) {
            redisTemplate.expire(key, authProperties.webSocketTicket.issueRateLimitWindow)
        }
        return count <= limit
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

    private fun rateLimitUserKey(userId: Long): String {
        return "${authProperties.webSocketTicket.rateLimitKeyPrefix}user:$userId"
    }

    private fun rateLimitIpKey(clientIp: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clientIp.toByteArray(StandardCharsets.UTF_8))
        return "${authProperties.webSocketTicket.rateLimitKeyPrefix}ip:${encoder.encodeToString(digest)}"
    }

    private data class StoredWebSocketTicket(
        val userId: Long = 0,
        val issuedAtEpochSecond: Long = 0,
        val expiresAtEpochSecond: Long = 0,
        val clientIp: String? = null,
    )

    private companion object {
        const val TICKET_BYTE_LENGTH = 32
        const val MIN_TICKET_LENGTH = 32
        const val MAX_TICKET_LENGTH = 512
        const val MAX_TICKET_GENERATION_ATTEMPTS = 3
    }
}
