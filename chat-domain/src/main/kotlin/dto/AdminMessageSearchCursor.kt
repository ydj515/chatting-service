package com.chat.domain.dto

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

data class AdminMessageCursor(
    val createdAt: Instant,
    val roomSeq: Long,
    val messageId: String,
)

typealias AdminMessageSearchCursor = AdminMessageCursor
typealias MessageHistoryCursor = AdminMessageCursor

object AdminMessageCursorCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(cursor: AdminMessageCursor): String {
        require(cursor.messageId.isNotBlank()) { "messageId must not be blank" }
        val encodedMessageId = encodeText(cursor.messageId)
        val payload = listOf(
            VERSION,
            cursor.createdAt.toString(),
            cursor.roomSeq.toString(),
            encodedMessageId,
        ).joinToString(SEPARATOR)
        return encodeText(payload)
    }

    fun decode(value: String?): AdminMessageCursor? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val payload = decodeText(normalized)
            val parts = payload.split(SEPARATOR)
            if (parts.size != EXPECTED_PARTS || parts[0] != VERSION) {
                throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)
            }
            val messageId = decodeText(parts[3])
            if (messageId.isBlank()) {
                throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)
            }
            AdminMessageCursor(
                createdAt = Instant.parse(parts[1]),
                roomSeq = parts[2].toLong(),
                messageId = messageId,
            )
        } catch (e: Exception) {
            throw IllegalArgumentException(INVALID_CURSOR_MESSAGE, e)
        }
    }

    private fun encodeText(value: String): String {
        return encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeText(value: String): String {
        return String(decoder.decode(value), StandardCharsets.UTF_8)
    }

    private const val VERSION = "v1"
    private const val SEPARATOR = "\t"
    private const val EXPECTED_PARTS = 4
    private const val INVALID_CURSOR_MESSAGE = "Invalid admin message cursor"
}

object AdminMessageSearchCursorCodec {
    fun encode(cursor: AdminMessageSearchCursor): String {
        return AdminMessageCursorCodec.encode(cursor)
    }

    fun decode(value: String?): AdminMessageSearchCursor? {
        return AdminMessageCursorCodec.decode(value)
    }
}

object MessageHistoryCursorCodec {
    fun encode(cursor: MessageHistoryCursor): String {
        return AdminMessageCursorCodec.encode(cursor)
    }

    fun decode(value: String?): MessageHistoryCursor? {
        return try {
            AdminMessageCursorCodec.decode(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid message history cursor", e)
        }
    }
}
