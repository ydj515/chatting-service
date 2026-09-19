package com.chat.worker.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration(proxyBeanMethods = false)
class WorkerSchedulingConfig {
    @Bean fun writerScheduler() = scheduler("writer-")

    @Bean fun fanoutScheduler() = scheduler("fanout-")

    @Bean fun exportScheduler() = scheduler("export-")

    @Bean fun maintenanceScheduler() = scheduler("maintenance-")

    @Bean fun sanctionCacheScheduler() = scheduler("sanction-cache-")

    private fun scheduler(prefix: String) = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix(prefix)
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
    }
}
