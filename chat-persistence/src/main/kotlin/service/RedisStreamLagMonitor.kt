package com.chat.persistence.service

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.redis.RedisStreamGroupLagSnapshot
import com.chat.persistence.redis.RedisStreamLagReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RedisStreamLagMonitor(
    private val lagReader: RedisStreamLagReader,
    private val workerProperties: ChatWorkerProperties,
    private val messageStreamMetrics: MessageStreamMetrics = MessageStreamMetrics.Noop,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun poll() {
        val snapshots = runCatching {
            lagReader.read(
                setOf(
                    workerProperties.writer.consumerGroup,
                    workerProperties.fanout.consumerGroup,
                ),
            )
        }.getOrElse { throwable ->
            logger.warn("Failed to poll Redis Streams direct lag gauges: ${throwable.message}")
            return
        }

        snapshots
            .groupBy { snapshot -> StreamGroup(snapshot.streamShard, snapshot.consumerGroup) }
            .forEach { (streamGroup, groupSnapshots) ->
                messageStreamMetrics.updateStreamGroupPending(
                    streamShard = streamGroup.streamShard,
                    consumerGroup = streamGroup.consumerGroup,
                    pending = groupSnapshots.sumOf { snapshot -> snapshot.pending },
                )

                val lagValues = groupSnapshots.mapNotNull(RedisStreamGroupLagSnapshot::lag)
                if (lagValues.isNotEmpty()) {
                    messageStreamMetrics.updateStreamGroupLag(
                        streamShard = streamGroup.streamShard,
                        consumerGroup = streamGroup.consumerGroup,
                        lag = lagValues.sum(),
                    )
                }
            }
    }

    private data class StreamGroup(
        val streamShard: Int?,
        val consumerGroup: String,
    )
}
