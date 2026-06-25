package com.chat.persistence.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class GatewaySendQueueRoomPolicySignalProviderTest {

    @Test
    fun `exposes gateway send queue depth as room policy signal`() {
        val sessionManager = mock(WebSocketSessionManager::class.java)
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

    private fun providerOf(sessionManager: WebSocketSessionManager?): ObjectProvider<WebSocketSessionManager> {
        return object : ObjectProvider<WebSocketSessionManager> {
            override fun getObject(vararg args: Any?): WebSocketSessionManager =
                sessionManager ?: throw IllegalStateException("no session manager")
            override fun getObject(): WebSocketSessionManager =
                sessionManager ?: throw IllegalStateException("no session manager")
            override fun getIfAvailable(): WebSocketSessionManager? = sessionManager
            override fun getIfUnique(): WebSocketSessionManager? = sessionManager
            override fun iterator(): MutableIterator<WebSocketSessionManager> =
                listOfNotNull(sessionManager).toMutableList().iterator()
            override fun stream(): Stream<WebSocketSessionManager> = Stream.ofNullable(sessionManager)
            override fun orderedStream(): Stream<WebSocketSessionManager> = Stream.ofNullable(sessionManager)
        }
    }
}
