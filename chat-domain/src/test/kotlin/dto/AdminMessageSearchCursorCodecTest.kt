package com.chat.domain.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminMessageSearchCursorCodecTest {

    @Test
    fun `admin message cursor codec은 createdAt roomSeq messageId를 opaque 문자열로 왕복한다`() {
        val cursor = AdminMessageCursor(
            createdAt = Instant.parse("2026-06-14T00:00:01.123456Z"),
            roomSeq = 1001L,
            messageId = "msg:with:separator",
        )

        val encoded = AdminMessageCursorCodec.encode(cursor)

        assertNotEquals("2026-06-14T00:00:01.123456Z", encoded)
        assertEquals(cursor, AdminMessageCursorCodec.decode(encoded))
    }

    @Test
    fun `admin message cursor codec은 잘못된 cursor를 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdminMessageCursorCodec.decode("not-a-valid-cursor")
        }
    }
}
