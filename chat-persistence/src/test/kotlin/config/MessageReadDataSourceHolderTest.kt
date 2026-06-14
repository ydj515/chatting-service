package com.chat.persistence.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MessageReadDataSourceHolderTest {

    @Test
    fun `read datasource가 활성화된 bean은 url을 요구한다`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            MessageReadDataSourceHolder(
                ChatReadDataSourceProperties(
                    enabled = true,
                    url = "",
                    username = "chatuser",
                    password = "chatpass",
                ),
            )
        }

        assertEquals(
            "chat.datasource.read.url must be configured when chat.datasource.read.enabled=true",
            exception.message,
        )
    }
}
