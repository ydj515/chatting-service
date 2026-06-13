package com.chat.persistence.redis

interface MessageStreamProducer {
    fun append(envelope: MessageStreamEnvelope): String
}
