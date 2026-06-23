package com.chat.persistence.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class MessageStreamMetricsTest {

    @Test
    fun `stream metrics records append latency with bounded stream shard and outcome tags`() {
        val meterRegistry = SimpleMeterRegistry()
        val metrics = MessageStreamMetrics(meterRegistryProvider(meterRegistry))

        metrics.recordAppend(streamShard = 3, outcome = "success", durationNanos = 1_000)
        metrics.recordAppend(streamShard = 3, outcome = "success", durationNanos = 2_000)

        val timer = meterRegistry.find("chat.redis.stream.append.latency")
            .tag("stream_shard", "3")
            .tag("outcome", "success")
            .timer()

        assertEquals(2, timer?.count())
        assertEquals(
            1,
            meterRegistry.meters.count { meter -> meter.id.name == "chat.redis.stream.append.latency" },
        )
    }

    @Test
    fun `stream metrics records consumer records without raw stream keys`() {
        val meterRegistry = SimpleMeterRegistry()
        val metrics = MessageStreamMetrics(meterRegistryProvider(meterRegistry))

        metrics.recordConsumerRecords(
            consumerGroup = "fanout",
            source = "pending_claimed",
            streamShard = 0,
            count = 2,
        )

        val counter = meterRegistry.find("chat.redis.stream.consumer.records")
            .tag("consumer_group", "fanout")
            .tag("source", "pending_claimed")
            .tag("stream_shard", "0")
            .counter()

        assertEquals(2.0, counter?.count())
        assertEquals(
            emptyList<String>(),
            meterRegistry.meters.flatMap { meter ->
                meter.id.tags.map { tag -> tag.value }.filter { value -> value.contains("chat:stream:") }
            },
        )
    }

    @Test
    fun `stream metrics records worker batch latency and record outcomes`() {
        val meterRegistry = SimpleMeterRegistry()
        val metrics = MessageStreamMetrics(meterRegistryProvider(meterRegistry))

        metrics.recordWorkerBatch(
            workerRole = "message-writer",
            outcome = "success",
            recordCount = 4,
            durationNanos = 2_000,
        )

        val timer = meterRegistry.find("chat.redis.stream.worker.batch.latency")
            .tag("worker_role", "message-writer")
            .tag("outcome", "success")
            .timer()
        val counter = meterRegistry.find("chat.redis.stream.worker.records")
            .tag("worker_role", "message-writer")
            .tag("outcome", "success")
            .counter()

        assertEquals(1, timer?.count())
        assertEquals(4.0, counter?.count())
    }

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
