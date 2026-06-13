package com.chat.persistence.service

import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class MessageWriterWorkerTest {

    @Test
    fun `writer worker는 stream record를 write port 요청으로 변환하고 저장 성공 시 ack한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord()),
        )
        val writePort = FakeMessageWritePort()
        val worker = workerFixture(consumer, writePort)

        val written = worker.pollAndWrite()

        assertEquals(1, written)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer"), consumer.ensuredGroups)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:worker-1"), consumer.reads)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-0"), consumer.acked)

        val request = writePort.requestBatches.single().single()
        assertEquals("msg-1", request.messageId)
        assertEquals("client-1", request.clientMessageId)
        assertEquals(10L, request.chatRoomId)
        assertEquals(7L, request.senderId)
        assertEquals(MessageType.TEXT, request.messageType)
        assertEquals("hello", request.content)
        assertEquals(11L, request.sequenceNumber)
        assertEquals(11L, request.roomSeq)
        assertEquals(0, request.streamShard)
        assertEquals(1, request.writeShard)
        assertEquals(2, request.fanoutShard)
        assertEquals(LocalDateTime.parse("2026-06-13T12:00:00"), request.createdAt)
    }

    @Test
    fun `writer worker는 write port가 duplicate 결과를 반환하면 저장 건수 없이 ack한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord()),
        )
        val writePort = FakeMessageWritePort { requests ->
            MessageWriteResult(
                outcomes = requests.map { request ->
                    MessageWriteOutcome(request = request, written = false)
                },
            )
        }
        val worker = workerFixture(consumer, writePort)

        val written = worker.pollAndWrite()

        assertEquals(0, written)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-0"), consumer.acked)
    }

    @Test
    fun `writer worker는 pending record를 claim해 재처리한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = emptyList(),
            claimedRecords = listOf(streamRecord(recordId = "1749790000000-2", deliveryCount = 2)),
        )
        val writePort = FakeMessageWritePort()
        val worker = workerFixture(consumer, writePort)

        val written = worker.pollAndWrite()

        assertEquals(1, written)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:worker-1:30000"), consumer.claims)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-2"), consumer.acked)
    }

    @Test
    fun `writer worker는 빠른 연속 poll에서 pending claim을 주기적으로만 수행한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = emptyList(),
            claimedRecords = listOf(streamRecord(recordId = "1749790000000-2", deliveryCount = 2)),
        )
        val writePort = FakeMessageWritePort { requests ->
            MessageWriteResult(
                outcomes = requests.map { request ->
                    MessageWriteOutcome(request = request, written = false)
                },
            )
        }
        val worker = workerFixture(consumer, writePort)

        worker.pollAndWrite()
        worker.pollAndWrite()

        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:worker-1:30000"), consumer.claims)
    }

    @Test
    fun `writer worker는 재시도 한계를 넘긴 저장 실패 record를 dead letter stream으로 보내고 ack한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = emptyList(),
            claimedRecords = listOf(streamRecord(recordId = "1749790000000-3", deliveryCount = 5)),
        )
        val writePort = FakeMessageWritePort {
            throw IllegalStateException("missing user")
        }
        val worker = workerFixture(consumer, writePort)

        val written = worker.pollAndWrite()

        assertEquals(0, written)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-3:missing user"), consumer.deadLetters)
        assertEquals(listOf("chat:stream:room:10:shard:0:message-writer:1749790000000-3"), consumer.acked)
    }

    private fun workerFixture(
        consumer: MessageStreamConsumer,
        writePort: MessageWritePort,
    ): MessageWriterWorker {
        return MessageWriterWorker(
            messageStreamConsumer = consumer,
            messageWritePort = writePort,
            workerProperties = ChatWorkerProperties(
                consumerName = "worker-1",
                writer = ChatWorkerProperties.StreamConsumer(
                    consumerGroup = "message-writer",
                    readCount = 10,
                    maxDeliveryCount = 3,
                    minIdleMillis = 30_000,
                    claimIntervalMillis = 10_000,
                ),
            ),
        )
    }

    private fun streamRecord(
        recordId: String = "1749790000000-0",
        deliveryCount: Long = 1,
    ): MessageStreamRecord {
        return MessageStreamRecord(
            streamKey = "chat:stream:room:10:shard:0",
            recordId = recordId,
            envelope = MessageStreamEnvelope(
                messageId = "msg-1",
                clientMessageId = "client-1",
                chatRoomId = 10L,
                senderId = 7L,
                senderName = "User 7",
                messageType = MessageType.TEXT,
                content = "hello",
                sequenceNumber = 11L,
                roomSeq = 11L,
                streamShard = 0,
                writeShard = 1,
                fanoutShard = 2,
                createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            ),
            deliveryCount = deliveryCount,
        )
    }

    private class FakeMessageWritePort(
        private val handler: (List<MessageWriteRequest>) -> MessageWriteResult = { requests ->
            MessageWriteResult(
                outcomes = requests.map { request ->
                    MessageWriteOutcome(request = request, written = true)
                },
            )
        },
    ) : MessageWritePort {
        val requestBatches = mutableListOf<List<MessageWriteRequest>>()

        override fun write(requests: List<MessageWriteRequest>): MessageWriteResult {
            requestBatches += requests
            return handler(requests)
        }
    }

    private class FakeMessageStreamConsumer(
        private val records: List<MessageStreamRecord>,
        private val claimedRecords: List<MessageStreamRecord> = emptyList(),
    ) : MessageStreamConsumer {
        val ensuredGroups = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val claims = mutableListOf<String>()
        val acked = mutableListOf<String>()
        val deadLetters = mutableListOf<String>()

        override fun listStreamKeys(): Set<String> {
            return (records + claimedRecords).mapTo(sortedSetOf()) { it.streamKey }
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

        override fun claimPending(
            consumerGroup: String,
            consumerName: String,
            streamKeys: Set<String>,
            count: Long,
            minIdleMillis: Long,
        ): List<MessageStreamRecord> {
            claims += "${streamKeys.first()}:$consumerGroup:$consumerName:$minIdleMillis"
            return claimedRecords
        }

        override fun sendToDeadLetter(
            record: MessageStreamRecord,
            consumerGroup: String,
            reason: String,
        ) {
            deadLetters += "${record.streamKey}:$consumerGroup:${record.recordId}:$reason"
        }
    }
}
