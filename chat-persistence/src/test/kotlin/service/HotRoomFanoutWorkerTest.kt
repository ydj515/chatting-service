package com.chat.persistence.service

import com.chat.domain.dto.ChatMessageBatch
import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import com.chat.persistence.redis.RedisMessageBroker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.LocalDateTime

class HotRoomFanoutWorkerTest {

    @Test
    fun `fanout worker는 stream 메시지를 방별 batch로 묶어 broadcast하고 ack한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(
                streamRecord(recordId = "1749790000000-0", roomSeq = 12L),
                streamRecord(recordId = "1749790000000-1", roomSeq = 11L),
            ),
        )
        val redisMessageBroker = mock(RedisMessageBroker::class.java)
        val worker = HotRoomFanoutWorker(
            messageStreamConsumer = consumer,
            redisMessageBroker = redisMessageBroker,
            workerProperties = ChatWorkerProperties(
                consumerName = "worker-1",
                fanout = ChatWorkerProperties.StreamConsumer(
                    consumerGroup = "fanout",
                    readCount = 10,
                ),
            ),
        )

        val broadcastCount = worker.pollAndFanout()

        assertEquals(1, broadcastCount)
        assertEquals(listOf("chat:stream:room:10:shard:0:fanout"), consumer.ensuredGroups)
        assertEquals(listOf("chat:stream:room:10:shard:0:fanout:worker-1"), consumer.reads)
        assertEquals(
            listOf(
                "chat:stream:room:10:shard:0:fanout:1749790000000-0",
                "chat:stream:room:10:shard:0:fanout:1749790000000-1",
            ),
            consumer.acked,
        )

        val batchCaptor = ArgumentCaptor.forClass(ChatMessageBatch::class.java)
        verify(redisMessageBroker).broadcastToRoom(
            roomId = org.mockito.ArgumentMatchers.eq(10L),
            message = captureBatch(batchCaptor),
            excludeServerId = org.mockito.ArgumentMatchers.isNull(),
        )
        val batch = batchCaptor.value
        assertEquals(10L, batch.chatRoomId)
        assertEquals(listOf(11L, 12L), batch.messages.map { it.roomSeq })
        assertEquals(listOf("msg-11", "msg-12"), batch.messages.map { it.messageId })
    }

    private fun streamRecord(recordId: String, roomSeq: Long): MessageStreamRecord {
        return MessageStreamRecord(
            streamKey = "chat:stream:room:10:shard:0",
            recordId = recordId,
            envelope = MessageStreamEnvelope(
                messageId = "msg-$roomSeq",
                clientMessageId = "client-$roomSeq",
                chatRoomId = 10L,
                senderId = 7L,
                senderName = "User 7",
                messageType = MessageType.TEXT,
                content = "hello-$roomSeq",
                sequenceNumber = roomSeq,
                roomSeq = roomSeq,
                streamShard = 0,
                writeShard = 1,
                fanoutShard = 2,
                createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            ),
        )
    }

    private class FakeMessageStreamConsumer(
        private val records: List<MessageStreamRecord>,
    ) : MessageStreamConsumer {
        val ensuredGroups = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val acked = mutableListOf<String>()

        override fun listStreamKeys(): Set<String> {
            return records.mapTo(sortedSetOf()) { it.streamKey }
        }

        override fun ensureConsumerGroup(streamKey: String, consumerGroup: String) {
            ensuredGroups += "$streamKey:$consumerGroup"
        }

        override fun readNew(
            consumerGroup: String,
            consumerName: String,
            streamKeys: Set<String>,
            count: Long,
        ): List<MessageStreamRecord> {
            reads += "${streamKeys.first()}:$consumerGroup:$consumerName"
            return records
        }

        override fun acknowledge(streamKey: String, consumerGroup: String, recordId: String) {
            acked += "$streamKey:$consumerGroup:$recordId"
        }
    }

    private fun captureBatch(captor: ArgumentCaptor<ChatMessageBatch>): ChatMessageBatch {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
