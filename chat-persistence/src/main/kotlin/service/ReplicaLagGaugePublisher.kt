package com.chat.persistence.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * read replica lag을 `chat.postgres.replica.lag` gauge로 노출한다.
 *
 * Prometheus scrape 시점에 동기 DB 쿼리를 실행하면 DB 지연/커넥션 고갈이 actuator scrape 응답
 * 지연으로 번질 수 있다. 따라서 전용 ScheduledExecutorService가 백그라운드에서 주기적으로 lag를
 * 측정해 캐시(AtomicLong)에 쓰고, gauge는 캐시 값만 읽는다. @Scheduled는 @EnableScheduling이 있는
 * worker 모듈에서만 동작하므로 자체 executor를 써서 모든 role에서 동일하게 갱신한다.
 *
 * 측정 실패는 0으로 은닉하지 않는다. 마지막으로 성공한 값을 유지하고 경고를 남겨, dashboard에서
 * "정상 0"과 "측정 실패"가 구분되도록 한다.
 */
@Service
class ReplicaLagGaugePublisher(
    private val readReplicaLagPolicy: ReadReplicaLagPolicy,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val logger = LoggerFactory.getLogger(ReplicaLagGaugePublisher::class.java)
    private val lagHolder = AtomicLong(0)
    private val registered = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "replica-lag-gauge").apply { isDaemon = true }
        }

    @PostConstruct
    fun start() {
        registerGauge()
        scheduler.scheduleWithFixedDelay(::refresh, 0, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    fun registerGauge() {
        if (!registered.compareAndSet(false, true)) {
            return
        }
        meterRegistryProvider?.ifAvailable { registry ->
            Gauge.builder("chat.postgres.replica.lag", lagHolder) { it.get().toDouble() }
                .tag(TAG_REPLICA, "read-replica")
                .register(registry)
        }
    }

    fun refresh() {
        try {
            lagHolder.set(readReplicaLagPolicy.currentLagMillis().coerceAtLeast(0))
        } catch (e: Exception) {
            logger.warn("Failed to measure read replica lag; keeping last known value {}ms", lagHolder.get(), e)
        }
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
    }

    private companion object {
        const val TAG_REPLICA = "replica"
        const val REFRESH_INTERVAL_SECONDS = 5L
    }
}
