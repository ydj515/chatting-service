package com.chat.persistence.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service
class MessageStreamMetrics(
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val appendTimers = ConcurrentHashMap<AppendTimerKey, Timer>()
    private val streamGroupGauges = ConcurrentHashMap<StreamGroupGaugeKey, AtomicLong>()

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

    fun updateStreamGroupLag(streamShard: Int?, consumerGroup: String, lag: Long) {
        updateStreamGroupGauge(
            metricName = "chat.redis.stream.group.lag",
            streamShard = streamShard,
            consumerGroup = consumerGroup,
            value = lag,
        )
    }

    fun updateStreamGroupPending(streamShard: Int?, consumerGroup: String, pending: Long) {
        updateStreamGroupGauge(
            metricName = "chat.redis.stream.group.pending",
            streamShard = streamShard,
            consumerGroup = consumerGroup,
            value = pending,
        )
    }

    private fun updateStreamGroupGauge(
        metricName: String,
        streamShard: Int?,
        consumerGroup: String,
        value: Long,
    ) {
        meterRegistryProvider?.ifAvailable { registry ->
            val tagStreamShard = streamShard?.toString() ?: TAG_VALUE_UNKNOWN
            val holder = streamGroupGauges.computeIfAbsent(
                StreamGroupGaugeKey(
                    registry = registry,
                    metricName = metricName,
                    streamShard = tagStreamShard,
                    consumerGroup = consumerGroup,
                ),
            ) {
                AtomicLong(0).also { gaugeValue ->
                    Gauge.builder(metricName, gaugeValue) { currentValue -> currentValue.get().toDouble() }
                        .tag(TAG_STREAM_SHARD, tagStreamShard)
                        .tag(TAG_CONSUMER_GROUP, consumerGroup)
                        .register(registry)
                }
            }
            holder.set(value.coerceAtLeast(0))
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

    private data class StreamGroupGaugeKey(
        val registry: MeterRegistry,
        val metricName: String,
        val streamShard: String,
        val consumerGroup: String,
    )
}
