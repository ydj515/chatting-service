package com.chat.persistence.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class WebSocketGatewayMetricsTest {

    @Test
    fun `registers connection and send queue depth gauges with gatewayGroup tag`() {
        val registry = SimpleMeterRegistry()
        val metrics = WebSocketGatewayMetrics("default", provider(registry))

        metrics.registerGauges(
            connectionCount = { 7 },
            roomSubscriptionCount = { 3 },
            sendQueueDepth = { 11 },
        )

        assertEquals(
            7.0,
            registry.find("chat.websocket.gateway.connections").tag("gatewayGroup", "default").gauge()?.value(),
        )
        assertEquals(
            3.0,
            registry.find("chat.websocket.gateway.room.subscriptions").tag("gatewayGroup", "default").gauge()?.value(),
        )
        assertEquals(
            11.0,
            registry.find("chat.websocket.gateway.send.queue.depth").tag("gatewayGroup", "default").gauge()?.value(),
        )
    }

    @Test
    fun `records local delivery, outbound bytes, write latency, slow client disconnect`() {
        val registry = SimpleMeterRegistry()
        val metrics = WebSocketGatewayMetrics("default", provider(registry))

        metrics.recordLocalDelivery(5)
        metrics.recordOutboundBytes(2048)
        metrics.recordWriteLatency(1_000_000, "success")
        metrics.recordSlowClientDisconnect()

        assertEquals(
            5.0,
            registry.find("chat.websocket.gateway.local.deliveries").tag("gatewayGroup", "default").counter()?.count(),
        )
        assertEquals(
            2048.0,
            registry.find("chat.websocket.gateway.outbound.bytes").tag("gatewayGroup", "default").counter()?.count(),
        )
        assertEquals(
            1L,
            registry.find("chat.websocket.gateway.write.latency").tag("outcome", "success").timer()?.count(),
        )
        assertEquals(
            1.0,
            registry.find("chat.websocket.gateway.slow_client.disconnects").tag("gatewayGroup", "default").counter()?.count(),
        )
    }

    private fun provider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
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
