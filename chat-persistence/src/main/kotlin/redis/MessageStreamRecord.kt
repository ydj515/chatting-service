package com.chat.persistence.redis

data class MessageStreamRecord(
    val streamKey: String,
    val recordId: String,
    val envelope: MessageStreamEnvelope,
)
