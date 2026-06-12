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
        val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
        }

        val configuration = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(cacheProperties.defaultTtl)
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(GenericJackson2JsonRedisSerializer(objectMapper)))
            .disableCachingNullValues()

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(configuration)
            .withCacheConfiguration("users", configuration.entryTtl(cacheProperties.usersTtl))
            .withCacheConfiguration("chatRooms", configuration.entryTtl(cacheProperties.chatRoomsTtl))
            .withCacheConfiguration("chatRoomMembers", configuration.entryTtl(cacheProperties.chatRoomMembersTtl))
            .withCacheConfiguration("messages", configuration.entryTtl(cacheProperties.messagesTtl))
            .build()
    }
}
