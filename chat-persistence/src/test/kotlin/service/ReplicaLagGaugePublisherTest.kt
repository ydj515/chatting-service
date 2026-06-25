package com.chat.persistence.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class ReplicaLagGaugePublisherTest {

    @Test
    fun `gauge reflects the last background measurement`() {
        val registry = SimpleMeterRegistry()
        val lagPolicy = mock(ReadReplicaLagPolicy::class.java)
        `when`(lagPolicy.currentLagMillis()).thenReturn(1500L)
        val publisher = ReplicaLagGaugePublisher(lagPolicy, provider(registry))

        publisher.registerGauge()
        publisher.refresh()

        assertEquals(
            1500.0,
            registry.find("chat.postgres.replica.lag").tag("replica", "read-replica").gauge()?.value(),
        )
    }

    @Test
    fun `keeps last known value and does not crash when measurement fails`() {
        val registry = SimpleMeterRegistry()
        val lagPolicy = mock(ReadReplicaLagPolicy::class.java)
        `when`(lagPolicy.currentLagMillis())
            .thenReturn(1200L)
            .thenThrow(RuntimeException("replica down"))
        val publisher = ReplicaLagGaugePublisher(lagPolicy, provider(registry))

        publisher.registerGauge()
        publisher.refresh() // 1200 측정 성공
        publisher.refresh() // 예외 발생, 마지막 값 유지

        assertEquals(
            1200.0,
            registry.find("chat.postgres.replica.lag").tag("replica", "read-replica").gauge()?.value(),
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
