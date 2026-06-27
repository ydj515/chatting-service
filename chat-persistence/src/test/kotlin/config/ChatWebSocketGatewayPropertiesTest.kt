package com.chat.persistence.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChatWebSocketGatewayPropertiesTest {

    @Test
    fun `heartbeat interval은 양수여야 한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatWebSocketGatewayProperties(
                heartbeatIntervalMillis = 0,
            )
        }
    }

    @Test
    fun `heartbeat scheduler poll interval은 양수여야 한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatWebSocketGatewayProperties(
                heartbeatSchedulerPollIntervalMillis = 0,
            )
        }
    }

    @Test
    fun `heartbeat scheduler poll interval은 heartbeat interval보다 짧아야 한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatWebSocketGatewayProperties(
                heartbeatIntervalMillis = 30_000,
                heartbeatSchedulerPollIntervalMillis = 30_000,
            )
        }
    }

    @Test
    fun `heartbeat timeout은 heartbeat interval보다 길어야 한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatWebSocketGatewayProperties(
                heartbeatIntervalMillis = 30_000,
                heartbeatTimeoutMillis = 30_000,
            )
        }
    }
}
