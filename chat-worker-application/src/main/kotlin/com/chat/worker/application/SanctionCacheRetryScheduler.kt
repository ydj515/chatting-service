package com.chat.worker.application

import com.chat.persistence.service.SanctionCacheInvalidator
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SanctionCacheRetryScheduler(private val invalidator: SanctionCacheInvalidator) {
    // Every worker may drain the queue; database leases prevent simultaneous ownership.
    @Scheduled(fixedDelayString = "\${chat.cache.sanction-retry.poll-delay-millis:5000}", scheduler = "sanctionCacheScheduler")
    fun retryPending() {
        invalidator.retryPending()
    }
}
