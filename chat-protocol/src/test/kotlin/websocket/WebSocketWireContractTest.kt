package com.chat.protocol.websocket

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSocketWireContractTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val chat = """{
        "type":"CHAT_MESSAGE","id":12,"messageId":"message-12","clientMessageId":"client-12",
        "content":"hello","messageType":"TEXT","senderId":7,"senderName":"Tester",
        "sequenceNumber":13,"roomSeq":13,"streamShard":0,"writeShard":0,"fanoutShard":0,
        "chatRoomId":10,"timestamp":"2026-09-25T00:00:00"
    }"""

    @Test
    fun `legacy single message retains discriminator and all fields`() {
        assertTrue(roundTrip(chat) is ChatMessage)
    }

    @Test
    fun `legacy batch retains nested discriminators and nullable room id`() {
        val json = """{"type":"CHAT_MESSAGE_BATCH","messages":[$chat],"chatRoomId":null,"timestamp":"2026-09-25T00:00:00"}"""
        val result = roundTrip(json) as ChatMessageBatch
        assertEquals("client-12", result.messages.single().clientMessageId)
    }

    @Test
    fun `legacy acceptance retains both room ids and sequence fields`() {
        val json = """{
            "type":"MESSAGE_ACCEPTED","id":12,"messageId":"message-12","clientMessageId":null,
            "roomId":10,"roomSeq":13,"sequenceNumber":13,"chatRoomId":10,"timestamp":"2026-09-25T00:00:00"
        }"""
        assertTrue(roundTrip(json) is MessageAccepted)
    }

    @Test
    fun `legacy error retains nullable code and room id`() {
        val json = """{"type":"ERROR","message":"Invalid input","code":null,"chatRoomId":null,"timestamp":"2026-09-25T00:00:00"}"""
        assertTrue(roundTrip(json) is ErrorMessage)
    }

    private fun roundTrip(json: String): WebSocketMessage {
        val message = mapper.readValue(json, WebSocketMessage::class.java)
        val encoded = mapper.writerFor(WebSocketMessage::class.java).writeValueAsString(message)
        assertEquals(mapper.readTree(json), mapper.readTree(encoded))
        return message
    }
}
