package com.chat.websocket.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Configuration
class WebSocketGatewayConfig(
    private val gatewayProperties: ChatWebSocketGatewayProperties,
) {
    @Bean("webSocketHeartbeatExecutor")
    fun webSocketHeartbeatScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("websocket-heartbeat-")
        setRemoveOnCancelPolicy(true)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    @Bean("webSocketOutboundExecutor", destroyMethod = "shutdownNow")
    fun webSocketOutboundExecutor(): ExecutorService {
        val threads = gatewayProperties.outboundExecutorThreads
        val sequence = AtomicLong()
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "websocket-outbound-${sequence.incrementAndGet()}").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(gatewayProperties.outboundExecutorQueueCapacity),
            factory, ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
