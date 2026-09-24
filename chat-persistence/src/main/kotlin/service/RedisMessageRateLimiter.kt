package com.chat.persistence.service

import com.chat.core.message.policy.AdmissionPolicy
import com.chat.core.message.port.AdmissionDecision
import com.chat.core.message.port.MessageRateLimiter
import com.chat.persistence.config.ChatRedisProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class RedisMessageRateLimiter(
    private val redisTemplate: RedisTemplate<String, String>,
    private val redisProperties: ChatRedisProperties,
    private val clock: Clock,
) : MessageRateLimiter {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val script: RedisScript<Long> = DefaultRedisScript(
        ADMISSION_SCRIPT,
        Long::class.javaObjectType,
    )

    override fun acquire(roomId: Long, senderId: Long, policy: AdmissionPolicy): AdmissionDecision {
        val epochSecond = clock.instant().epochSecond
        val roomRateLimit = policy.roomRateLimitPerSecond.positiveOrZero()
        val userRateLimit = policy.userRateLimitPerSecond.positiveOrZero()
        val slowModeMillis = policy.slowModeSeconds.positiveOrZero() * MILLIS_PER_SECOND
        val keys = listOf(
            roomRateKey(roomId, epochSecond),
            userRateKey(roomId, senderId, epochSecond),
            slowModeKey(roomId, senderId),
        )

        val result = try {
            redisTemplate.execute(
                script,
                keys,
                redisProperties.admission.rateLimitWindowTtl.toMillis().toString(),
                roomRateLimit.toString(),
                userRateLimit.toString(),
                slowModeMillis.toString(),
                senderId.toString(),
            )
        } catch (e: Exception) {
            logger.warn("Failed to evaluate message admission policy roomId={} senderId={}", roomId, senderId, e)
            return AdmissionDecision.Unavailable(e)
        }

        return when (result) {
            RESULT_ALLOWED -> AdmissionDecision.Allowed
            RESULT_ROOM_RATE_LIMITED -> AdmissionDecision.RoomRateLimited
            RESULT_USER_RATE_LIMITED -> AdmissionDecision.UserRateLimited
            RESULT_SLOW_MODE_ACTIVE -> AdmissionDecision.SlowModeActive
            else -> AdmissionDecision.Unavailable()
        }
    }

    private fun roomRateKey(roomId: Long, epochSecond: Long): String = "${redisProperties.admission.keyPrefix}{$roomId}:rate:room:$epochSecond"

    private fun userRateKey(roomId: Long, senderId: Long, epochSecond: Long): String = "${redisProperties.admission.keyPrefix}{$roomId}:rate:user:$senderId:$epochSecond"

    private fun slowModeKey(roomId: Long, senderId: Long): String = "${redisProperties.admission.keyPrefix}{$roomId}:slow:user:$senderId"

    private fun Int?.positiveOrZero(): Int = this?.takeIf { it > 0 } ?: 0

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val RESULT_ALLOWED = 0L
        const val RESULT_ROOM_RATE_LIMITED = 1L
        const val RESULT_USER_RATE_LIMITED = 2L
        const val RESULT_SLOW_MODE_ACTIVE = 3L
        const val ADMISSION_SCRIPT = """
            local windowMillis = tonumber(ARGV[1])
            local roomLimit = tonumber(ARGV[2])
            local userLimit = tonumber(ARGV[3])
            local slowModeMillis = tonumber(ARGV[4])
            local slowModeValue = ARGV[5]

            if slowModeMillis > 0 and redis.call('EXISTS', KEYS[3]) == 1 then
              return 3
            end

            if roomLimit > 0 then
              local currentRoomCount = tonumber(redis.call('GET', KEYS[1]) or '0')
              if currentRoomCount >= roomLimit then
                return 1
              end
            end

            if userLimit > 0 then
              local currentUserCount = tonumber(redis.call('GET', KEYS[2]) or '0')
              if currentUserCount >= userLimit then
                return 2
              end
            end

            if roomLimit > 0 then
              local roomCount = redis.call('INCR', KEYS[1])
              local roomTtl = redis.call('PTTL', KEYS[1])
              if roomCount == 1 or roomTtl == -1 then
                redis.call('PEXPIRE', KEYS[1], windowMillis)
              end
            end

            if userLimit > 0 then
              local userCount = redis.call('INCR', KEYS[2])
              local userTtl = redis.call('PTTL', KEYS[2])
              if userCount == 1 or userTtl == -1 then
                redis.call('PEXPIRE', KEYS[2], windowMillis)
              end
            end

            if slowModeMillis > 0 then
              redis.call('SET', KEYS[3], slowModeValue, 'PX', slowModeMillis)
            end

            return 0
        """
    }
}
