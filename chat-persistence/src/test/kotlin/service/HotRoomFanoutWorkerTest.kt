package com.chat.persistence.service

import com.chat.domain.dto.ChatMessageBatch
import com.chat.domain.model.MessageType
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.MessageStreamConsumer
import com.chat.persistence.redis.MessageStreamEnvelope
import com.chat.persistence.redis.MessageStreamRecord
import com.chat.persistence.redis.RedisMessageBroker
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.ObjectProvider
import java.time.LocalDateTime
import java.util.stream.Stream

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
        assertEquals(listOf(11L, 12L), batch.messages.map { it.id })
        assertEquals(listOf("msg-11", "msg-12"), batch.messages.map { it.messageId })
    }

    @Test
    fun `fanout worker는 빠른 연속 poll에서 pending claim을 주기적으로만 수행한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = emptyList(),
            claimedRecords = listOf(streamRecord(recordId = "1749790000000-0", roomSeq = 12L)),
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
                    claimIntervalMillis = 10_000,
                ),
            ),
        )

        worker.pollAndFanout()
        worker.pollAndFanout()

        assertEquals(listOf("chat:stream:room:10:shard:0:fanout:worker-1:30000"), consumer.claims)
    }

    @Test
    fun `fanout worker는 owner lease를 획득하지 못한 stream을 읽거나 claim하지 않는다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord(recordId = "1749790000000-0", roomSeq = 12L)),
        )
        val redisMessageBroker = mock(RedisMessageBroker::class.java)
        val leaseService = FakeFanoutOwnerLeaseService(acquireResult = null)
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
            fanoutOwnerLeaseService = leaseService,
        )

        val broadcastCount = worker.pollAndFanout()

        assertEquals(0, broadcastCount)
        assertEquals(listOf("10:0"), leaseService.acquireAttempts)
        assertEquals(emptyList<String>(), consumer.ensuredGroups)
        assertEquals(emptyList<String>(), consumer.reads)
        assertEquals(emptyList<String>(), consumer.claims)
        assertEquals(emptyList<String>(), consumer.acked)
        verifyNoInteractions(redisMessageBroker)
    }

    @Test
    fun `fanout worker는 여러 stream shard에 대해 owner lease를 각각 획득하고 batch를 publish한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(
                streamRecord(recordId = "1749790000000-0", roomSeq = 1L, streamShard = 0),
                streamRecord(recordId = "1749790000001-0", roomSeq = 2L, streamShard = 1),
            ),
        )
        val redisMessageBroker = mock(RedisMessageBroker::class.java)
        val leaseService = PerShardFanoutOwnerLeaseService()
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
            fanoutOwnerLeaseService = leaseService,
        )

        val broadcastCount = worker.pollAndFanout()

        assertEquals(2, broadcastCount)
        assertEquals(listOf("10:0", "10:1"), leaseService.acquireAttempts)
        assertEquals(
            listOf(
                "chat:stream:room:10:shard:0:fanout",
                "chat:stream:room:10:shard:1:fanout",
            ),
            consumer.ensuredGroups,
        )
        assertEquals(
            listOf(
                "chat:stream:room:10:shard:0:fanout:1749790000000-0",
                "chat:stream:room:10:shard:1:fanout:1749790000001-0",
            ),
            consumer.acked,
        )
    }

    @Test
    fun `fanout worker는 publish 직전 owner token이 유효하지 않으면 broadcast와 ack를 하지 않는다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord(recordId = "1749790000000-0", roomSeq = 12L)),
        )
        val redisMessageBroker = mock(RedisMessageBroker::class.java)
        val leaseService = FakeFanoutOwnerLeaseService(
            acquireResult = FanoutOwnerLease(
                key = "chat:fanout:owner:room:10:shard:0",
                value = "worker-1:token",
                roomId = 10L,
                streamShard = 0,
            ),
            validationResults = mutableMapOf(
                FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH to false,
            ),
        )
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
            fanoutOwnerLeaseService = leaseService,
        )

        val broadcastCount = worker.pollAndFanout()

        assertEquals(0, broadcastCount)
        assertEquals(listOf("chat:stream:room:10:shard:0:fanout:worker-1"), consumer.reads)
        assertEquals(listOf(FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH), leaseService.validations)
        assertEquals(emptyList<String>(), consumer.acked)
        verifyNoInteractions(redisMessageBroker)
    }

    @Test
    fun `fanout worker는 publish 후 ack 직전 owner token이 유효하지 않으면 ack하지 않는다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord(recordId = "1749790000000-0", roomSeq = 12L)),
        )
        val redisMessageBroker = mock(RedisMessageBroker::class.java)
        val leaseService = FakeFanoutOwnerLeaseService(
            acquireResult = FanoutOwnerLease(
                key = "chat:fanout:owner:room:10:shard:0",
                value = "worker-1:token",
                roomId = 10L,
                streamShard = 0,
            ),
            validationResults = mutableMapOf(
                FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH to true,
                FanoutOwnerLeaseValidationStage.BEFORE_ACK to false,
            ),
        )
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
            fanoutOwnerLeaseService = leaseService,
        )

        val broadcastCount = worker.pollAndFanout()

        assertEquals(1, broadcastCount)
        assertEquals(
            listOf(
                FanoutOwnerLeaseValidationStage.BEFORE_PUBLISH,
                FanoutOwnerLeaseValidationStage.BEFORE_ACK,
            ),
            leaseService.validations,
        )
        assertEquals(emptyList<String>(), consumer.acked)
        verify(redisMessageBroker).broadcastToRoom(
            roomId = org.mockito.ArgumentMatchers.eq(10L),
            message = anyBatch(),
            excludeServerId = org.mockito.ArgumentMatchers.isNull(),
        )
    }

    @Test
    fun `fanout worker는 처리 latency와 record outcome metric을 기록한다`() {
        val meterRegistry = SimpleMeterRegistry()
        val consumer = FakeMessageStreamConsumer(
            records = listOf(streamRecord(recordId = "1749790000000-0", roomSeq = 12L)),
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
            messageStreamMetrics = MessageStreamMetrics(meterRegistryProvider(meterRegistry)),
        )

        worker.pollAndFanout()

        val timer = meterRegistry.find("chat.redis.stream.worker.batch.latency")
            .tag("worker_role", "fanout")
            .tag("outcome", "success")
            .timer()
        val counter = meterRegistry.find("chat.redis.stream.worker.records")
            .tag("worker_role", "fanout")
            .tag("outcome", "success")
            .counter()

        assertEquals(1, timer?.count())
        assertEquals(1.0, counter?.count())
    }

    private fun streamRecord(
        recordId: String,
        roomSeq: Long,
        streamShard: Int = 0,
    ): MessageStreamRecord {
        return MessageStreamRecord(
            streamKey = "chat:stream:room:10:shard:$streamShard",
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
                streamShard = streamShard,
                writeShard = 1,
                fanoutShard = streamShard,
                createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            ),
        )
    }

    private class FakeMessageStreamConsumer(
        private val records: List<MessageStreamRecord>,
        private val claimedRecords: List<MessageStreamRecord> = emptyList(),
    ) : MessageStreamConsumer {
        val ensuredGroups = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val claims = mutableListOf<String>()
        val acked = mutableListOf<String>()

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
            reads += "${streamKeys.sorted().joinToString(",")}:$consumerGroup:$consumerName"
            return records.filter { it.streamKey in streamKeys }
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
            claims += "${streamKeys.sorted().joinToString(",")}:$consumerGroup:$consumerName:$minIdleMillis"
            return claimedRecords.filter { it.streamKey in streamKeys }
        }

        override fun sendToDeadLetter(
            record: MessageStreamRecord,
            consumerGroup: String,
            reason: String,
        ) {
            error("dead letter is not expected in this test")
        }
    }

    private class FakeFanoutOwnerLeaseService(
        private val acquireResult: FanoutOwnerLease?,
        private val validationResults: MutableMap<FanoutOwnerLeaseValidationStage, Boolean> = mutableMapOf(),
    ) : FanoutOwnerLeaseService {
        val acquireAttempts = mutableListOf<String>()
        val validations = mutableListOf<FanoutOwnerLeaseValidationStage>()

        override fun acquire(roomId: Long, streamShard: Int): FanoutOwnerLease? {
            acquireAttempts += "$roomId:$streamShard"
            return acquireResult
        }

        override fun validate(
            lease: FanoutOwnerLease,
            stage: FanoutOwnerLeaseValidationStage,
        ): Boolean {
            validations += stage
            return validationResults[stage] ?: true
        }

        override fun release(lease: FanoutOwnerLease) = Unit
    }

    private class PerShardFanoutOwnerLeaseService : FanoutOwnerLeaseService {
        val acquireAttempts = mutableListOf<String>()

        override fun acquire(roomId: Long, streamShard: Int): FanoutOwnerLease? {
            acquireAttempts += "$roomId:$streamShard"
            return FanoutOwnerLease(
                key = "chat:fanout:owner:room:$roomId:shard:$streamShard",
                value = "worker-1:token-$streamShard",
                roomId = roomId,
                streamShard = streamShard,
            )
        }

        override fun validate(
            lease: FanoutOwnerLease,
            stage: FanoutOwnerLeaseValidationStage,
        ): Boolean = true

        override fun release(lease: FanoutOwnerLease) = Unit
    }

    private fun captureBatch(captor: ArgumentCaptor<ChatMessageBatch>): ChatMessageBatch {
        captor.capture()
        return uninitialized()
    }

    private fun anyBatch(): ChatMessageBatch {
        org.mockito.ArgumentMatchers.any(ChatMessageBatch::class.java)
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T

    private fun meterRegistryProvider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry = meterRegistry
            override fun getObject(vararg args: Any?): MeterRegistry = meterRegistry
            override fun getIfAvailable(): MeterRegistry = meterRegistry
            override fun getIfUnique(): MeterRegistry = meterRegistry
            override fun iterator(): MutableIterator<MeterRegistry> = mutableListOf(meterRegistry).iterator()
            override fun stream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
            override fun orderedStream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
        }
    }
}
