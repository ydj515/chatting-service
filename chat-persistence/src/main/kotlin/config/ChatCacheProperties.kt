package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.cache")
data class ChatCacheProperties(
    val defaultTtl: Duration = Duration.ofMinutes(30),
    val usersTtl: Duration = Duration.ofHours(1),
    val chatRoomsTtl: Duration = Duration.ofMinutes(15),
    val chatRoomMembersTtl: Duration = Duration.ofMinutes(10),
    val messagesTtl: Duration = Duration.ofMinutes(5),
    val roomAdmissionPoliciesTtl: Duration = Duration.ofSeconds(10),
    val roomShardConfigsTtl: Duration = Duration.ofSeconds(10),
    val moderationRulesTtl: Duration = Duration.ofSeconds(10),
    val userSanctionsTtl: Duration = Duration.ofSeconds(10),
)
