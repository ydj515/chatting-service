package com.chat.persistence.redis

interface MessageStreamConsumer {
    fun listStreamKeys(): Set<String>

    fun ensureConsumerGroup(streamKey: String, consumerGroup: String)

    fun readNew(
        consumerGroup: String,
        consumerName: String,
        streamKeys: Set<String>,
        count: Long,
    ): List<MessageStreamRecord>

    fun claimPending(
        consumerGroup: String,
        consumerName: String,
        streamKeys: Set<String>,
        count: Long,
        minIdleMillis: Long,
    ): List<MessageStreamRecord>

    fun acknowledge(streamKey: String, consumerGroup: String, recordId: String)

    fun sendToDeadLetter(
        record: MessageStreamRecord,
        consumerGroup: String,
        reason: String,
    )
}
