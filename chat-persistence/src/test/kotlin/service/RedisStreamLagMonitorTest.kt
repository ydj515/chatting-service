package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.RedisStreamGroupLagSnapshot
import com.chat.persistence.redis.RedisStreamLagReader
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class RedisStreamLagMonitorTest {

    @Test
    fun `stream shard와 consumer group 단위로 lag와 pending gauge를 합산한다`() {
        val meterRegistry = SimpleMeterRegistry()
        val monitor = RedisStreamLagMonitor(
            lagReader = FakeRedisStreamLagReader(
                listOf(
                    RedisStreamGroupLagSnapshot(streamShard = 0, consumerGroup = "message-writer", lag = 3, pending = 2),
                    RedisStreamGroupLagSnapshot(streamShard = 0, consumerGroup = "message-writer", lag = 4, pending = 5),
                    RedisStreamGroupLagSnapshot(streamShard = 1, consumerGroup = "fanout", lag = 8, pending = 1),
                ),
            ),
            workerProperties = ChatWorkerProperties(),
            messageStreamMetrics = MessageStreamMetrics(meterRegistryProvider(meterRegistry)),
        )

        monitor.poll()

        assertEquals(
            7.0,
            meterRegistry.find("chat.redis.stream.group.lag")
                .tag("stream_shard", "0")
                .tag("consumer_group", "message-writer")
                .gauge()
                ?.value(),
        )
        assertEquals(
            7.0,
            meterRegistry.find("chat.redis.stream.group.pending")
                .tag("stream_shard", "0")
                .tag("consumer_group", "message-writer")
                .gauge()
                ?.value(),
        )
        assertEquals(
            emptyList<String>(),
            meterRegistry.meters.flatMap { meter ->
                meter.id.tags.map { tag -> tag.value }.filter { value -> value.contains("chat:stream:") }
            },
        )
    }

    private class FakeRedisStreamLagReader(
        private val snapshots: List<RedisStreamGroupLagSnapshot>,
    ) : RedisStreamLagReader {
        override fun read(consumerGroups: Set<String>): List<RedisStreamGroupLagSnapshot> {
            return snapshots.filter { snapshot -> consumerGroups.contains(snapshot.consumerGroup) }
        }
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
