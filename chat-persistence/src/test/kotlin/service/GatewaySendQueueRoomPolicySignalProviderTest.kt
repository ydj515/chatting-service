package com.chat.persistence.service

import com.chat.core.gateway.port.LocalGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class GatewaySendQueueRoomPolicySignalProviderTest {
    @Test
    fun `exposes gateway send queue depth as room policy signal`() {
        val sessionManager = mock(LocalGateway::class.java)
        `when`(sessionManager.currentSendQueueDepth()).thenReturn(42)
        val provider = GatewaySendQueueRoomPolicySignalProvider(providerOf(sessionManager))

        val signals = provider.signals(roomId = 10L)

        assertEquals(42, signals.gatewaySendQueueDepth)
    }

    @Test
    fun `returns zero depth when no session manager bean is available`() {
        val provider = GatewaySendQueueRoomPolicySignalProvider(providerOf(null))

        assertEquals(0, provider.signals(roomId = 10L).gatewaySendQueueDepth)
    }

    private fun providerOf(sessionManager: LocalGateway?): ObjectProvider<LocalGateway> =
        object : ObjectProvider<LocalGateway> {
            override fun getObject(vararg args: Any?): LocalGateway =
                sessionManager ?: error("no session manager")

            override fun getObject(): LocalGateway =
                sessionManager ?: error("no session manager")

            override fun getIfAvailable(): LocalGateway? = sessionManager

            override fun getIfUnique(): LocalGateway? = sessionManager

            override fun iterator(): MutableIterator<LocalGateway> =
                listOfNotNull(sessionManager).toMutableList().iterator()

            override fun stream(): Stream<LocalGateway> = Stream.ofNullable(sessionManager)

            override fun orderedStream(): Stream<LocalGateway> = Stream.ofNullable(sessionManager)
        }
}
