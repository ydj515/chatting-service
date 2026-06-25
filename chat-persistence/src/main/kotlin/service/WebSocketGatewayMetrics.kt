package com.chat.persistence.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service
class WebSocketGatewayMetrics(
    @Value("\${chat.websocket.gateway.gateway-group:default}")
    private val gatewayGroup: String = "default",
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val gaugesRegistered = AtomicBoolean(false)
    private val writeTimers = ConcurrentHashMap<String, Timer>()

    // gauge supplier는 세션 매니저가 제공한다. Gauge 등록은 멱등이 아니므로 1회만 수행한다.
    fun registerGauges(
        connectionCount: () -> Number,
        roomSubscriptionCount: () -> Number,
        sendQueueDepth: () -> Number,
    ) {
        if (!gaugesRegistered.compareAndSet(false, true)) {
            return
        }
        meterRegistryProvider?.ifAvailable { registry ->
            Gauge.builder("chat.websocket.gateway.connections") { connectionCount().toDouble() }
                .tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
            Gauge.builder("chat.websocket.gateway.room.subscriptions") { roomSubscriptionCount().toDouble() }
                .tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
            Gauge.builder("chat.websocket.gateway.send.queue.depth") { sendQueueDepth().toDouble() }
                .tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
        }
    }

    fun recordLocalDelivery(count: Int) {
        if (count <= 0) return
        counter("chat.websocket.gateway.local.deliveries")?.increment(count.toDouble())
    }

    fun recordOutboundBytes(bytes: Long) {
        if (bytes <= 0) return
        counter("chat.websocket.gateway.outbound.bytes")?.increment(bytes.toDouble())
    }

    fun recordBatchFrame() {
        counter("chat.websocket.gateway.batch.frames")?.increment()
    }

    fun recordSlowClientDisconnect() {
        counter("chat.websocket.gateway.slow_client.disconnects")?.increment()
    }

    fun recordWriteLatency(durationNanos: Long, outcome: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            writeTimers.computeIfAbsent(outcome) {
                Timer.builder("chat.websocket.gateway.write.latency")
                    .tag(TAG_GATEWAY_GROUP, gatewayGroup)
                    .tag(TAG_OUTCOME, outcome)
                    .register(registry)
            }.record(maxOf(durationNanos, 0L), TimeUnit.NANOSECONDS)
        }
    }

    private fun counter(name: String): Counter? {
        var result: Counter? = null
        // Counter.builder(...).register(registry)는 동일 name/tag에 대해 멱등이라 같은 meter를 반환한다.
        meterRegistryProvider?.ifAvailable { registry ->
            result = Counter.builder(name).tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
        }
        return result
    }

    companion object {
        val Noop = WebSocketGatewayMetrics(gatewayGroup = "default")

        private const val TAG_GATEWAY_GROUP = "gatewayGroup"
        private const val TAG_OUTCOME = "outcome"
    }
}
