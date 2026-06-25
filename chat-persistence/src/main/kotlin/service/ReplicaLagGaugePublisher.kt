package com.chat.persistence.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * read replica lag을 `chat.postgres.replica.lag` gauge로 노출한다.
 *
 * @Scheduled는 @EnableScheduling이 있는 worker 실행 모듈에서만 동작하므로, 모든 role에서 동일하게
 * 관측되도록 scrape 시점에 측정하는 supplier 기반 gauge로 등록한다. 측정 쿼리는
 * pg_last_xact_replay_timestamp 기반의 가벼운 쿼리이며 실패 시 0으로 떨어진다.
 */
@Service
class ReplicaLagGaugePublisher(
    private val readReplicaLagPolicy: ReadReplicaLagPolicy,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val registered = AtomicBoolean(false)

    @PostConstruct
    fun register() {
        if (!registered.compareAndSet(false, true)) {
            return
        }
        meterRegistryProvider?.ifAvailable { registry ->
            Gauge.builder("chat.postgres.replica.lag") { measureLagSafely().toDouble() }
                .tag(TAG_REPLICA, "read-replica")
                .register(registry)
        }
    }

    fun measureLagSafely(): Long =
        runCatching { readReplicaLagPolicy.currentLagMillis() }.getOrDefault(0L)

    private companion object {
        const val TAG_REPLICA = "replica"
    }
}
