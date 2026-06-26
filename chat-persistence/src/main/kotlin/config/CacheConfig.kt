package com.chat.persistence.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
@EnableCaching
class CacheConfig(
    private val cacheProperties: ChatCacheProperties,
) {

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): CacheManager {
        val configuration = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(cacheProperties.defaultTtl)
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisCacheValueSerializer()))
            .disableCachingNullValues()

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(configuration)
            .withCacheConfiguration("users", configuration.entryTtl(cacheProperties.usersTtl))
            .withCacheConfiguration("chatRooms", configuration.entryTtl(cacheProperties.chatRoomsTtl))
            .withCacheConfiguration("chatRoomMembers", configuration.entryTtl(cacheProperties.chatRoomMembersTtl))
            .withCacheConfiguration("messages", configuration.entryTtl(cacheProperties.messagesTtl))
            .withCacheConfiguration("roomAdmissionPolicies", configuration.entryTtl(cacheProperties.roomAdmissionPoliciesTtl))
            .withCacheConfiguration("roomShardConfigs", configuration.entryTtl(cacheProperties.roomShardConfigsTtl))
            .withCacheConfiguration("moderationRules", configuration.entryTtl(cacheProperties.moderationRulesTtl))
            .withCacheConfiguration("userSanctions", configuration.entryTtl(cacheProperties.userSanctionsTtl))
            .build()
    }

    companion object {
        fun redisCacheValueSerializer(): GenericJackson2JsonRedisSerializer {
            val objectMapper = ObjectMapper().apply {
                registerModule(KotlinModule.Builder().build())
                registerModule(JavaTimeModule())
            }

            return GenericJackson2JsonRedisSerializer.builder()
                .objectMapper(objectMapper)
                .defaultTyping(true)
                .build()
        }
    }
}
