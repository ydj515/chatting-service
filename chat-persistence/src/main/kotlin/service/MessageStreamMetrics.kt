package com.chat.persistence.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class MessageStreamMetrics(
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val appendTimers = ConcurrentHashMap<AppendTimerKey, Timer>()

    fun recordAppend(streamShard: Int, outcome: String, durationNanos: Long) {
        meterRegistryProvider?.ifAvailable { registry ->
            val timer = appendTimers.computeIfAbsent(AppendTimerKey(registry, streamShard, outcome)) {
                Timer.builder("chat.redis.stream.append.latency")
                    .tag(TAG_STREAM_SHARD, streamShard.toString())
                    .tag(TAG_OUTCOME, outcome)
                    .register(registry)
            }
            timer.record(maxOf(durationNanos, 0L), TimeUnit.NANOSECONDS)
        }
    }

    fun recordConsumerRecords(
        consumerGroup: String,
        source: String,
        streamShard: Int?,
        count: Int,
    ) {
        if (count <= 0) {
            return
        }
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.redis.stream.consumer.records")
                .tag(TAG_CONSUMER_GROUP, consumerGroup)
                .tag(TAG_SOURCE, source)
                .tag(TAG_STREAM_SHARD, streamShard?.toString() ?: TAG_VALUE_UNKNOWN)
                .register(registry)
                .increment(count.toDouble())
        }
    }

    fun recordWorkerBatch(
        workerRole: String,
        outcome: String,
        recordCount: Int,
        durationNanos: Long,
    ) {
        meterRegistryProvider?.ifAvailable { registry ->
            Timer.builder("chat.redis.stream.worker.batch.latency")
                .tag(TAG_WORKER_ROLE, workerRole)
                .tag(TAG_OUTCOME, outcome)
                .register(registry)
                .record(maxOf(durationNanos, 0L), TimeUnit.NANOSECONDS)

            if (recordCount > 0) {
                Counter.builder("chat.redis.stream.worker.records")
                    .tag(TAG_WORKER_ROLE, workerRole)
                    .tag(TAG_OUTCOME, outcome)
                    .register(registry)
                    .increment(recordCount.toDouble())
            }
        }
    }

    fun recordDeadLetter(consumerGroup: String, streamShard: Int?) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.redis.stream.dead_letters")
                .tag(TAG_CONSUMER_GROUP, consumerGroup)
                .tag(TAG_STREAM_SHARD, streamShard?.toString() ?: TAG_VALUE_UNKNOWN)
                .register(registry)
                .increment()
        }
    }

    companion object {
        val Noop = MessageStreamMetrics()

        private const val TAG_CONSUMER_GROUP = "consumer_group"
        private const val TAG_OUTCOME = "outcome"
        private const val TAG_SOURCE = "source"
        private const val TAG_STREAM_SHARD = "stream_shard"
        private const val TAG_VALUE_UNKNOWN = "unknown"
        private const val TAG_WORKER_ROLE = "worker_role"
    }

    private data class AppendTimerKey(
        val registry: MeterRegistry,
        val streamShard: Int,
        val outcome: String,
    )
}
