package com.chat.persistence.redis

interface MessageStreamProducer {
    fun findAccepted(roomId: Long, senderId: Long, clientMessageId: String): MessageStreamEnvelope?

    fun append(envelope: MessageStreamEnvelope): MessageStreamEnvelope
}
