package com.chat.persistence.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class WebSocketGatewayConfig(
    private val gatewayProperties: ChatWebSocketGatewayProperties,
) {
    @Bean("webSocketOutboundExecutor", destroyMethod = "shutdown")
    fun webSocketOutboundExecutor(): ExecutorService {
        val threads = gatewayProperties.outboundExecutorThreads.coerceAtLeast(1)
        return Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable).apply {
                name = "websocket-outbound-${System.currentTimeMillis()}"
                isDaemon = true
            }
        }
    }
}
