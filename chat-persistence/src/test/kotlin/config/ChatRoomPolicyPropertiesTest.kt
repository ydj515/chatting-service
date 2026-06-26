package com.chat.persistence.config

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatRoomPolicyPropertiesTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `shard count 설정은 1 이상이어야 한다`() {
        val violations = validator.validate(
            ChatRoomPolicyProperties(
                hotShardCount = 0,
                veryHotShardCount = 0,
            ),
        )

        assertTrue(violations.any { it.propertyPath.toString() == "hotShardCount" })
        assertTrue(violations.any { it.propertyPath.toString() == "veryHotShardCount" })
    }

    @Test
    fun `very hot shard count는 hot shard count보다 작을 수 없다`() {
        val violations = validator.validate(
            ChatRoomPolicyProperties(
                hotShardCount = 16,
                veryHotShardCount = 8,
            ),
        )

        assertTrue(violations.any { it.propertyPath.toString() == "veryHotShardCountAtLeastHotShardCount" })
    }
}
