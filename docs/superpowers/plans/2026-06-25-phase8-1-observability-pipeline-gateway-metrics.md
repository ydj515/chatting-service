# Phase 8.1 Observability Pipeline + Gateway/Fan-out Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 7에서 만든 metric 계측을 실제 수집/시각화 파이프라인(Prometheus + Grafana)에 연결하고, 현재 비어 있는 Gateway/Fan-out 실측 metric을 추가해 release gate를 관측 가능하게 만든다.

**Architecture:** (1) `micrometer-registry-prometheus`를 추가하고 actuator `prometheus` 엔드포인트를 노출해 scrape 포맷을 생성한다. (2) Docker Compose에 Prometheus와 Grafana를 추가해 role별 scrape와 기존 alert rule을 연결한다. (3) 기존 `MessageStreamMetrics`와 동일한 `ObjectProvider<MeterRegistry>` + `Noop` 패턴으로 `WebSocketGatewayMetrics` 컴포넌트를 만들고 `WebSocketSessionManager`에 배선한다. (4) Gateway send queue depth를 실측해 `RoomPolicySignalProvider`로 연결, OVERLOAD 자동 판정 입력을 채운다.

**Tech Stack:** Kotlin, Spring Boot, Micrometer, Gradle version catalog(`gradle/libs.versions.toml`), Docker Compose, Prometheus, Grafana.

## Global Constraints

- Kotlin + Spring Boot. 새 라이브러리는 반드시 `gradle/libs.versions.toml`에 등록 후 `libs.*`로 참조한다. 버전은 `spring-boot-dependencies` BOM(version catalog line 8)이 관리하므로 명시하지 않는다.
- 새 metric 이름은 `chat.*` prefix를 쓴다.
- metric tag cardinality 통제(`docs/observability_metrics.md` 2장): `userId`, `sessionId`, `messageId`, `clientMessageId`, raw IP, session token, ticket, message body를 tag로 쓰지 않는다. `roomId`도 무제한 tag로 쓰지 않는다. 허용 tag는 `gatewayGroup`, `roomHeat`, `outcome`, `reason`, `scope` 같은 bounded 값뿐이다.
- 모든 metric 컴포넌트는 `MessageStreamMetrics`와 동일하게 `ObjectProvider<MeterRegistry>?` 생성자와 `Noop` companion을 둬서 registry 없이도 NPE 없이 동작한다.
- 테스트는 `SimpleMeterRegistry`로 실제 등록을 검증한다(`MessageStreamMetricsTest` 패턴).
- TDD: 실패 테스트 → 최소 구현 → 통과 → 커밋. 커밋은 task 단위.

---

## File Structure

| 파일 | 책임 | 생성/수정 |
| --- | --- | --- |
| `gradle/libs.versions.toml` | `micrometer-registry-prometheus` 라이브러리 좌표 등록 | 수정 |
| `chat-persistence/build.gradle.kts` | persistence 모듈에 prometheus registry 의존성 추가 | 수정 |
| `chat-runtime-config/src/main/resources/application-docker.yml` | actuator `prometheus` 노출, gatewayGroup 설정 | 수정 |
| `infra/prometheus/prometheus.yml` | role별 scrape config, alert rule 로딩 | 생성 |
| `infra/grafana/provisioning/datasources/prometheus.yml` | Grafana가 Prometheus를 datasource로 자동 등록 | 생성 |
| `infra/grafana/provisioning/dashboards/dashboards.yml` | dashboard provider 설정 | 생성 |
| `infra/grafana/dashboards/chat-release-gate.json` | release gate 패널 dashboard | 생성 |
| `docker-compose.yml` | `prometheus`, `grafana` 서비스 추가 | 수정 |
| `chat-persistence/.../service/WebSocketGatewayMetrics.kt` | Gateway/Fan-out metric 등록 컴포넌트 | 생성 |
| `chat-persistence/.../service/WebSocketSessionManager.kt` | metric 컴포넌트를 세션 lifecycle/전송 경로에 배선 | 수정 |
| `chat-persistence/.../config/ChatWebSocketGatewayProperties.kt` | `gatewayGroup` 설정 필드 추가 | 수정 |
| `chat-persistence/.../service/GatewaySendQueueRoomPolicySignalProvider.kt` | send queue depth를 RoomPolicy signal로 노출 | 생성 |
| `chat-persistence/.../service/ReplicaLagGaugePublisher.kt` | replica lag을 Prometheus gauge로 주기 노출 | 생성 |
| 각 `*Test.kt` | TDD 검증 | 생성 |

---

## Task 1: Prometheus scrape 엔드포인트 활성화

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `chat-persistence/build.gradle.kts`
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`

**Interfaces:**
- Produces: `/api/actuator/prometheus` HTTP 엔드포인트(scrape 텍스트), 모든 role 애플리케이션에서 노출. `chat.*` metric이 `chat_*` 형식으로 export됨.

- [ ] **Step 1: version catalog에 prometheus registry 등록**

`gradle/libs.versions.toml`의 `micrometer-core` 줄(line 37) 바로 아래에 추가한다.

```toml
micrometer-core = { group = "io.micrometer", name = "micrometer-core" }
micrometer-registry-prometheus = { group = "io.micrometer", name = "micrometer-registry-prometheus" }
```

- [ ] **Step 2: persistence 모듈에 의존성 추가**

`chat-persistence/build.gradle.kts`의 `implementation(libs.micrometer.core)`(line 31) 아래에 추가한다.

```kotlin
implementation(libs.micrometer.core)
implementation(libs.micrometer.registry.prometheus)
```

- [ ] **Step 3: actuator prometheus 노출 설정**

`chat-runtime-config/src/main/resources/application-docker.yml`의 management 섹션에서 exposure include를 수정한다. 현재 값은 `health,info,metrics,caches`다.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,caches,prometheus
      cors:
        allowed-origins: ${MANAGEMENT_CORS_ALLOWED_ORIGINS:*}
        allowed-methods: ${MANAGEMENT_CORS_ALLOWED_METHODS:GET,POST,PUT,DELETE,OPTIONS}
        allowed-headers: ${MANAGEMENT_CORS_ALLOWED_HEADERS:*}
        max-age: ${MANAGEMENT_CORS_MAX_AGE:3600}
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: ${spring.application.name:chat}
```

- [ ] **Step 4: 빌드와 엔드포인트 확인**

Run: `./gradlew :chat-api-application:bootJar --no-daemon`
Expected: BUILD SUCCESSFUL

Run(앱 기동 후): `curl -s http://localhost/api/actuator/prometheus | grep -c '^chat_'`
Expected: 1 이상(기존 등록된 `chat_*` metric이 export됨)

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml chat-persistence/build.gradle.kts chat-runtime-config/src/main/resources/application-docker.yml
git commit -m "feat: expose prometheus scrape endpoint for chat metrics"
```

---

## Task 2: Prometheus + Grafana 컨테이너와 scrape config

**Files:**
- Create: `infra/prometheus/prometheus.yml`
- Create: `infra/grafana/provisioning/datasources/prometheus.yml`
- Create: `infra/grafana/provisioning/dashboards/dashboards.yml`
- Create: `infra/grafana/dashboards/chat-release-gate.json`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: Task 1의 `/api/actuator/prometheus` 엔드포인트.
- Produces: `prometheus`(scrape + alert rule), `grafana`(dashboard) 컨테이너. 둘 다 `chat-network`에 연결.

- [ ] **Step 1: prometheus scrape config 작성**

`infra/prometheus/prometheus.yml`을 생성한다. role별 서비스를 actuator 경로로 scrape하고, 기존 alert rule을 로딩한다.

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/rules/*.rules.yml

scrape_configs:
  - job_name: chat-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["chat-api-app-1:8080", "chat-api-app-2:8080"]
        labels: { role: chat-api }
  - job_name: chat-websocket
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["chat-websocket-app-1:8080", "chat-websocket-app-2:8080"]
        labels: { role: chat-websocket }
  - job_name: chat-worker
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["chat-worker-app-1:8080"]
        labels: { role: chat-worker }
  - job_name: chat-admin
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["chat-admin-app-1:8080"]
        labels: { role: chat-admin }
```

> 포트(`8080`)는 `SERVER_PORT` 설정과 일치시킨다. 각 app의 실제 컨테이너 내부 포트를 `docker-compose.yml`에서 확인해 맞춘다.

- [ ] **Step 2: Grafana datasource provisioning**

`infra/grafana/provisioning/datasources/prometheus.yml`을 생성한다.

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

- [ ] **Step 3: Grafana dashboard provider**

`infra/grafana/provisioning/dashboards/dashboards.yml`을 생성한다.

```yaml
apiVersion: 1
providers:
  - name: chat
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 4: release gate dashboard 최소본**

`infra/grafana/dashboards/chat-release-gate.json`을 생성한다. Redis Streams group lag 패널 하나로 시작하고, 이후 task에서 Gateway 패널을 추가한다.

```json
{
  "title": "Chat Release Gate",
  "uid": "chat-release-gate",
  "schemaVersion": 39,
  "panels": [
    {
      "type": "timeseries",
      "title": "Redis Streams group lag",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [
        { "expr": "chat_redis_stream_group_lag", "legendFormat": "{{stream_shard}}/{{consumer_group}}" }
      ]
    }
  ]
}
```

- [ ] **Step 5: docker-compose에 prometheus, grafana 추가**

`docker-compose.yml`의 `nginx` 서비스 정의 앞(또는 `redis` 다음)에 추가한다. `chat-network`에 연결한다.

```yaml
  prometheus:
    image: ${PROMETHEUS_IMAGE:-prom/prometheus:v2.53.0}
    container_name: chat-prometheus
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./infra/prometheus/rules:/etc/prometheus/rules:ro
      - prometheus_data:/prometheus
    ports:
      - "9090:9090"
    networks:
      - chat-network

  grafana:
    image: ${GRAFANA_IMAGE:-grafana/grafana:11.1.0}
    container_name: chat-grafana
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-admin}
      GF_USERS_ALLOW_SIGN_UP: "false"
    volumes:
      - ./infra/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./infra/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana_data:/var/lib/grafana
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
    networks:
      - chat-network
```

그리고 `volumes:` 블록(현재 `redis_data:`가 있는 곳)에 추가한다.

```yaml
  prometheus_data:
  grafana_data:
```

- [ ] **Step 6: 파이프라인 동작 확인**

Run: `docker compose up -d prometheus grafana`
Run: `curl -s 'http://localhost:9090/api/v1/targets' | grep -c '"health":"up"'`
Expected: scrape target이 up 상태(1 이상)

Run: `curl -s 'http://localhost:9090/api/v1/rules' | grep -c RedisStreamsGroupLag`
Expected: 1 이상(기존 alert rule이 로딩됨)

- [ ] **Step 7: Commit**

```bash
git add infra/prometheus infra/grafana docker-compose.yml
git commit -m "feat: add prometheus and grafana to compose with chat scrape config"
```

---

## Task 3: WebSocketGatewayMetrics 계측 컴포넌트

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/WebSocketGatewayMetrics.kt`
- Create: `chat-persistence/src/test/kotlin/service/WebSocketGatewayMetricsTest.kt`
- Modify: `chat-persistence/src/main/kotlin/config/ChatWebSocketGatewayProperties.kt`

**Interfaces:**
- Consumes: `ObjectProvider<MeterRegistry>` (Spring), `gatewayGroup: String`.
- Produces:
  - `registerGauges(connectionCount: () -> Number, roomSubscriptionCount: () -> Number, sendQueueDepth: () -> Number)`
  - `recordLocalDelivery(count: Int)`
  - `recordOutboundBytes(bytes: Long)`
  - `recordBatchFrame()`
  - `recordWriteLatency(durationNanos: Long, outcome: String)`
  - `recordSlowClientDisconnect()`
  - `companion object { val Noop = WebSocketGatewayMetrics(gatewayGroup = "default") }`
  - metric 이름: `chat.websocket.gateway.connections`(Gauge), `chat.websocket.gateway.room.subscriptions`(Gauge), `chat.websocket.gateway.send.queue.depth`(Gauge), `chat.websocket.gateway.local.deliveries`(Counter), `chat.websocket.gateway.outbound.bytes`(Counter), `chat.websocket.gateway.batch.frames`(Counter), `chat.websocket.gateway.write.latency`(Timer, tag `outcome`), `chat.websocket.gateway.slow_client.disconnects`(Counter). 모든 metric에 `gatewayGroup` tag.

- [ ] **Step 1: gatewayGroup 설정 필드 추가**

`chat-persistence/src/main/kotlin/config/ChatWebSocketGatewayProperties.kt`의 데이터 클래스에 필드를 추가한다(기존 필드 옆, 기본값 `"default"`).

```kotlin
val gatewayGroup: String = "default",
```

- [ ] **Step 2: 실패 테스트 작성**

`chat-persistence/src/test/kotlin/service/WebSocketGatewayMetricsTest.kt`를 생성한다.

```kotlin
package com.chat.persistence.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.concurrent.TimeUnit
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
            1,
            registry.find("chat.websocket.gateway.write.latency").tag("outcome", "success").timer()?.count(),
        )
        assertEquals(
            1.0,
            registry.find("chat.websocket.gateway.slow_client.disconnects").tag("gatewayGroup", "default").counter()?.count(),
        )
    }

    private fun provider(registry: MeterRegistry): ObjectProvider<MeterRegistry> =
        object : ObjectProvider<MeterRegistry> {
            override fun getObject(vararg args: Any?): MeterRegistry = registry
            override fun getObject(): MeterRegistry = registry
            override fun getIfAvailable(): MeterRegistry = registry
            override fun getIfUnique(): MeterRegistry = registry
            override fun stream(): Stream<MeterRegistry> = Stream.of(registry)
            override fun ifAvailable(consumer: java.util.function.Consumer<MeterRegistry>) = consumer.accept(registry)
        }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :chat-persistence:test --no-daemon --tests "com.chat.persistence.service.WebSocketGatewayMetricsTest"`
Expected: FAIL (`WebSocketGatewayMetrics` 미정의 컴파일 에러)

- [ ] **Step 4: 컴포넌트 구현**

`chat-persistence/src/main/kotlin/service/WebSocketGatewayMetrics.kt`를 생성한다. `MessageStreamMetrics`와 동일한 lazy 등록 패턴을 쓴다.

```kotlin
package com.chat.persistence.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service
class WebSocketGatewayMetrics(
    @Value("\${chat.websocket.gateway.gateway-group:default}")
    private val gatewayGroup: String = "default",
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val gaugesRegistered = AtomicBoolean(false)
    private val writeTimers = ConcurrentHashMap<String, Timer>()

    // gauge supplier는 세션 매니저가 제공한다. 등록은 1회만 수행한다.
    fun registerGauges(
        connectionCount: () -> Number,
        roomSubscriptionCount: () -> Number,
        sendQueueDepth: () -> Number,
    ) {
        if (!gaugesRegistered.compareAndSet(false, true)) {
            return
        }
        meterRegistryProvider?.ifAvailable { registry ->
            Gauge.builder("chat.websocket.gateway.connections") { connectionCount().toDouble() }
                .tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
            Gauge.builder("chat.websocket.gateway.room.subscriptions") { roomSubscriptionCount().toDouble() }
                .tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
            Gauge.builder("chat.websocket.gateway.send.queue.depth") { sendQueueDepth().toDouble() }
                .tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
        }
    }

    fun recordLocalDelivery(count: Int) {
        if (count <= 0) return
        counter("chat.websocket.gateway.local.deliveries")?.increment(count.toDouble())
    }

    fun recordOutboundBytes(bytes: Long) {
        if (bytes <= 0) return
        counter("chat.websocket.gateway.outbound.bytes")?.increment(bytes.toDouble())
    }

    fun recordBatchFrame() {
        counter("chat.websocket.gateway.batch.frames")?.increment()
    }

    fun recordSlowClientDisconnect() {
        counter("chat.websocket.gateway.slow_client.disconnects")?.increment()
    }

    fun recordWriteLatency(durationNanos: Long, outcome: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            writeTimers.computeIfAbsent("$outcome") {
                Timer.builder("chat.websocket.gateway.write.latency")
                    .tag(TAG_GATEWAY_GROUP, gatewayGroup)
                    .tag(TAG_OUTCOME, outcome)
                    .register(registry)
            }.record(maxOf(durationNanos, 0L), TimeUnit.NANOSECONDS)
        }
    }

    private fun counter(name: String): Counter? {
        var result: Counter? = null
        meterRegistryProvider?.ifAvailable { registry ->
            result = Counter.builder(name).tag(TAG_GATEWAY_GROUP, gatewayGroup).register(registry)
        }
        return result
    }

    companion object {
        val Noop = WebSocketGatewayMetrics(gatewayGroup = "default")

        private const val TAG_GATEWAY_GROUP = "gatewayGroup"
        private const val TAG_OUTCOME = "outcome"
    }
}
```

> 주의: `Counter.builder(...).register(registry)`는 같은 이름/tag에 대해 멱등이라 매 호출 재등록해도 같은 meter를 반환한다. Gauge는 멱등이 아니므로 `registerGauges`는 `AtomicBoolean`으로 1회만 등록한다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :chat-persistence:test --no-daemon --tests "com.chat.persistence.service.WebSocketGatewayMetricsTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/WebSocketGatewayMetrics.kt chat-persistence/src/test/kotlin/service/WebSocketGatewayMetricsTest.kt chat-persistence/src/main/kotlin/config/ChatWebSocketGatewayProperties.kt
git commit -m "feat: add WebSocketGatewayMetrics with bounded gatewayGroup tags"
```

---

## Task 4: WebSocketSessionManager에 Gateway metric 배선

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt`
- Create/Modify: `chat-persistence/src/test/kotlin/service/WebSocketSessionManagerMetricsTest.kt`

**Interfaces:**
- Consumes: Task 3의 `WebSocketGatewayMetrics`.
- Produces: 세션 연결 수/구독 방 수/send queue depth gauge가 실측값을 반영하고, 로컬 전송 시 delivery/outbound bytes/write latency가, slow client overflow 시 disconnect counter가 기록됨.

- [ ] **Step 1: 생성자에 metric 주입**

`WebSocketSessionManager` 생성자에 파라미터를 추가한다(기존 생성자 마지막 파라미터 뒤).

```kotlin
    @Qualifier("webSocketOutboundExecutor")
    private val outboundExecutor: Executor,
    private val gatewayMetrics: WebSocketGatewayMetrics = WebSocketGatewayMetrics.Noop,
) {
```

- [ ] **Step 2: gauge 등록을 initialize에 추가**

`@PostConstruct fun initialize()` 끝부분에 gauge 등록을 추가한다. send queue depth는 모든 세션 pending 합으로 계산한다.

```kotlin
        gatewayMetrics.registerGauges(
            connectionCount = { sessionsById.size },
            roomSubscriptionCount = { sessionIdsByRoomId.size },
            sendQueueDepth = { totalPendingSize() },
        )
    }

    private fun totalPendingSize(): Int =
        sessionsById.values.sumOf { it.outboundQueue.pendingSize() }
```

- [ ] **Step 3: 전송 경로에 delivery/bytes/latency 기록**

`sendMessageToLocalRoom`의 enqueue 성공 분기를 수정한다. 현재는 로그만 남긴다.

```kotlin
            if (sessionRef.outboundQueue.enqueue(json)) {
                gatewayMetrics.recordLocalDelivery(1)
                gatewayMetrics.recordOutboundBytes(json.toByteArray(Charsets.UTF_8).size.toLong())
            }
        }
        if (message.type == "CHAT_MESSAGE_BATCH") {
            gatewayMetrics.recordBatchFrame()
        }
```

> `message.type` 비교 문자열은 `WebSocketMessage`의 batch 타입 상수와 일치시킨다. 다르면 해당 상수를 import해 사용한다.

- [ ] **Step 4: write latency를 sender 래핑에 추가**

`addSession`의 `BoundedOutboundSessionQueue` 생성에서 `sender` lambda를 timing으로 감싼다.

```kotlin
                sender = { payload ->
                    val startNanos = System.nanoTime()
                    try {
                        outboundSession.sendMessage(TextMessage(payload))
                        gatewayMetrics.recordWriteLatency(System.nanoTime() - startNanos, "success")
                    } catch (t: Throwable) {
                        gatewayMetrics.recordWriteLatency(System.nanoTime() - startNanos, "failure")
                        throw t
                    }
                },
                onOverflow = {
                    logger.warn("Closing session ${session.id} because outbound queue is full")
                    gatewayMetrics.recordSlowClientDisconnect()
                    closeSession(session, OUTBOUND_QUEUE_FULL_STATUS)
                    removeSession(userId, session)
                },
```

- [ ] **Step 5: 실패 테스트 작성**

`chat-persistence/src/test/kotlin/service/WebSocketSessionManagerMetricsTest.kt`를 생성한다. 기존 `WebSocketSessionManagerTest`의 생성 방식을 참고해 의존성을 구성하고, `WebSocketGatewayMetrics(SimpleMeterRegistry)`를 주입한다. 한 세션에 메시지를 보낸 뒤 `chat.websocket.gateway.local.deliveries` counter가 1 이상인지 검증한다.

```kotlin
package com.chat.persistence.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSocketSessionManagerMetricsTest {

    @Test
    fun `local delivery counter increments when message enqueued to a local session`() {
        val registry = SimpleMeterRegistry()
        val harness = WebSocketSessionManagerTestHarness(registry) // 기존 테스트 헬퍼 패턴 재사용
        harness.connectUserToRoom(userId = 1L, roomId = 10L)

        harness.sessionManager.sendMessageToLocalRoom(10L, harness.sampleMessage())

        val delivered = registry.find("chat.websocket.gateway.local.deliveries").counter()?.count() ?: 0.0
        assertTrue(delivered >= 1.0)
    }
}
```

> `WebSocketSessionManagerTest`가 세션/Redis/repository를 어떻게 mock하는지 확인하고, 그 구성(mock `RedisTemplate`, `ChatRoomMemberRepository.existsBy...=true`, fake open `WebSocketSession`)을 `WebSocketSessionManagerTestHarness`로 추출하거나 동일하게 인라인한다. 새 mock 라이브러리를 추가하지 않는다.

- [ ] **Step 6: 테스트 실패 → 통과 확인**

Run: `./gradlew :chat-persistence:test --no-daemon --tests "com.chat.persistence.service.WebSocketSessionManagerMetricsTest"`
Expected: 먼저 FAIL(배선 전) 후, Step 1~4 적용 상태에서 PASS

- [ ] **Step 7: 전체 회귀 확인**

Run: `./gradlew :chat-persistence:test --no-daemon`
Expected: PASS (기존 `WebSocketSessionManagerTest` 포함 회귀 없음)

- [ ] **Step 8: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt chat-persistence/src/test/kotlin/service/WebSocketSessionManagerMetricsTest.kt
git commit -m "feat: wire gateway metrics into websocket session lifecycle and delivery"
```

---

## Task 5: send queue depth를 RoomPolicy signal로 연결 (OVERLOAD 입력)

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/GatewaySendQueueRoomPolicySignalProvider.kt`
- Create: `chat-persistence/src/test/kotlin/service/GatewaySendQueueRoomPolicySignalProviderTest.kt`

**Interfaces:**
- Consumes: `WebSocketSessionManager`의 send queue depth(Task 4에서 노출한 `totalPendingSize()`를 public 접근자로 승격), `RoomPolicySignals`/`RoomPolicySignalProvider`(기존).
- Produces: `@Primary`로 등록되는 `RoomPolicySignalProvider` 구현체. `signals(roomId).gatewaySendQueueDepth`가 실제 Gateway pending depth를 반환해 `RoomHeatClassifier`의 OVERLOAD 판정이 0 placeholder가 아닌 실측을 입력으로 받는다.

- [ ] **Step 1: 세션 매니저에 public depth 접근자 추가**

`WebSocketSessionManager`의 `totalPendingSize()`를 public으로 노출한다(이름 `currentSendQueueDepth`).

```kotlin
    fun currentSendQueueDepth(): Int = totalPendingSize()
```

- [ ] **Step 2: 실패 테스트 작성**

`chat-persistence/src/test/kotlin/service/GatewaySendQueueRoomPolicySignalProviderTest.kt`를 생성한다.

```kotlin
package com.chat.persistence.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GatewaySendQueueRoomPolicySignalProviderTest {

    @Test
    fun `exposes gateway send queue depth as room policy signal`() {
        val sessionManager = mock<WebSocketSessionManager>()
        whenever(sessionManager.currentSendQueueDepth()).thenReturn(42)
        val provider = GatewaySendQueueRoomPolicySignalProvider(sessionManager)

        val signals = provider.signals(roomId = 10L)

        assertEquals(42, signals.gatewaySendQueueDepth)
    }
}
```

> mock 라이브러리는 기존 테스트가 쓰는 것(`mockito-kotlin` 등)을 그대로 쓴다. `WebSocketSessionManagerTest`가 어떤 mock을 쓰는지 먼저 확인한다.

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :chat-persistence:test --no-daemon --tests "com.chat.persistence.service.GatewaySendQueueRoomPolicySignalProviderTest"`
Expected: FAIL (클래스 미정의)

- [ ] **Step 4: provider 구현**

`chat-persistence/src/main/kotlin/service/GatewaySendQueueRoomPolicySignalProvider.kt`를 생성한다.

```kotlin
package com.chat.persistence.service

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

// 현재 Gateway pending depth를 OVERLOAD 판정 신호로 노출한다.
// writer/fanout lag는 Redis Streams group lag gauge를 입력으로 연결하는 후속 슬라이스에서 채운다.
@Service
@Primary
class GatewaySendQueueRoomPolicySignalProvider(
    private val sessionManager: WebSocketSessionManager,
) : RoomPolicySignalProvider {
    override fun signals(roomId: Long): RoomPolicySignals =
        RoomPolicySignals(gatewaySendQueueDepth = sessionManager.currentSendQueueDepth())
}
```

> `@Primary`로 기존 `NoopRoomPolicySignalProvider`보다 우선 주입되게 한다. worker 모듈이 Gateway 세션 매니저 bean을 갖지 않는 런타임에서는 이 bean이 생성되지 않으므로, worker 컨텍스트에서는 Noop이 그대로 쓰인다(주입 가능성은 component scan 범위로 통제). worker 단독 컨텍스트에서 bean 충돌이 나면 `@Profile` 또는 `@ConditionalOnBean(WebSocketSessionManager::class)`로 좁힌다.

- [ ] **Step 5: 테스트 통과 + 회귀 확인**

Run: `./gradlew :chat-persistence:test --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/GatewaySendQueueRoomPolicySignalProvider.kt chat-persistence/src/test/kotlin/service/GatewaySendQueueRoomPolicySignalProviderTest.kt chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt
git commit -m "feat: feed real gateway send queue depth into room policy overload signal"
```

---

## Task 6: Replica lag을 Prometheus gauge로 노출

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/ReplicaLagGaugePublisher.kt`
- Create: `chat-persistence/src/test/kotlin/service/ReplicaLagGaugePublisherTest.kt`

**Interfaces:**
- Consumes: 기존 `ReadReplicaLagPolicy`(이미 `REPLICA_LAG_MILLIS_SQL`로 lag를 읽음), `ObjectProvider<MeterRegistry>`.
- Produces: `chat.postgres.replica.lag`(Gauge, tag `replica`) metric이 주기적으로 갱신됨. admin 응답의 `replicaLagMs` placeholder(`0::bigint`)를 대체하는 운영 관측 경로.

- [ ] **Step 1: ReadReplicaLagPolicy의 lag 접근자 확인**

`chat-persistence/src/main/kotlin/service/ReadReplicaLagPolicy.kt`를 읽어 현재 lag를 반환하는 public 메서드 이름을 확인한다. 없으면 `fun currentLagMillis(): Long`을 추가한다(기존 `REPLICA_LAG_MILLIS_SQL` 쿼리 재사용).

```kotlin
    fun currentLagMillis(): Long =
        messageReadJdbcTemplate.queryForObject(REPLICA_LAG_MILLIS_SQL, Long::class.java) ?: 0L
```

- [ ] **Step 2: 실패 테스트 작성**

`chat-persistence/src/test/kotlin/service/ReplicaLagGaugePublisherTest.kt`를 생성한다.

```kotlin
package com.chat.persistence.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class ReplicaLagGaugePublisherTest {

    @Test
    fun `publishes replica lag as a gauge`() {
        val registry = SimpleMeterRegistry()
        val lagPolicy = mock<ReadReplicaLagPolicy>()
        whenever(lagPolicy.currentLagMillis()).thenReturn(1500L)
        val publisher = ReplicaLagGaugePublisher(lagPolicy, provider(registry))

        publisher.publish()

        assertEquals(
            1500.0,
            registry.find("chat.postgres.replica.lag").tag("replica", "read-replica").gauge()?.value(),
        )
    }

    private fun provider(registry: MeterRegistry): ObjectProvider<MeterRegistry> =
        object : ObjectProvider<MeterRegistry> {
            override fun getObject(vararg args: Any?): MeterRegistry = registry
            override fun getObject(): MeterRegistry = registry
            override fun getIfAvailable(): MeterRegistry = registry
            override fun getIfUnique(): MeterRegistry = registry
            override fun stream(): Stream<MeterRegistry> = Stream.of(registry)
            override fun ifAvailable(consumer: java.util.function.Consumer<MeterRegistry>) = consumer.accept(registry)
        }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :chat-persistence:test --no-daemon --tests "com.chat.persistence.service.ReplicaLagGaugePublisherTest"`
Expected: FAIL

- [ ] **Step 4: publisher 구현**

`chat-persistence/src/main/kotlin/service/ReplicaLagGaugePublisher.kt`를 생성한다.

```kotlin
package com.chat.persistence.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

@Service
class ReplicaLagGaugePublisher(
    private val readReplicaLagPolicy: ReadReplicaLagPolicy,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) {
    private val lagHolder = AtomicLong(0)
    private val registered = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${chat.observability.replica-lag-publish-interval-ms:5000}")
    fun publish() {
        ensureRegistered()
        runCatching { readReplicaLagPolicy.currentLagMillis() }
            .onSuccess { lagHolder.set(it.coerceAtLeast(0)) }
    }

    private fun ensureRegistered() {
        if (!registered.compareAndSet(false, true)) return
        meterRegistryProvider?.ifAvailable { registry ->
            Gauge.builder("chat.postgres.replica.lag", lagHolder) { it.get().toDouble() }
                .tag("replica", "read-replica")
                .register(registry)
        }
    }
}
```

> `@Scheduled`가 동작하려면 worker/admin 실행 모듈에 `@EnableScheduling`이 있어야 한다. 이미 `RoomPolicyWorker` 등 스케줄 작업이 있으므로 활성화되어 있을 가능성이 높다. 없으면 해당 `@Configuration`에 추가한다.

- [ ] **Step 5: 테스트 통과 + 회귀 확인**

Run: `./gradlew :chat-persistence:test --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/ReplicaLagGaugePublisher.kt chat-persistence/src/test/kotlin/service/ReplicaLagGaugePublisherTest.kt chat-persistence/src/main/kotlin/service/ReadReplicaLagPolicy.kt
git commit -m "feat: publish postgres replica lag as prometheus gauge"
```

---

## Task 7: Grafana release gate dashboard에 Gateway 패널 추가

**Files:**
- Modify: `infra/grafana/dashboards/chat-release-gate.json`

**Interfaces:**
- Consumes: Task 3~6의 metric(`chat_websocket_gateway_*`, `chat_postgres_replica_lag`).
- Produces: release gate를 한 화면에서 보는 dashboard.

- [ ] **Step 1: Gateway/Replica 패널 추가**

`infra/grafana/dashboards/chat-release-gate.json`의 `panels` 배열에 패널을 추가한다(Prometheus가 `chat.` → `chat_`, `.` → `_`로 변환).

```json
    {
      "type": "timeseries",
      "title": "WebSocket write latency p95",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "targets": [
        { "expr": "histogram_quantile(0.95, sum(rate(chat_websocket_gateway_write_latency_seconds_bucket[1m])) by (le, gatewayGroup))", "legendFormat": "{{gatewayGroup}}" }
      ]
    },
    {
      "type": "timeseries",
      "title": "Active connections / send queue depth",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "targets": [
        { "expr": "chat_websocket_gateway_connections", "legendFormat": "connections {{gatewayGroup}}" },
        { "expr": "chat_websocket_gateway_send_queue_depth", "legendFormat": "queue {{gatewayGroup}}" }
      ]
    },
    {
      "type": "timeseries",
      "title": "Postgres replica lag (ms)",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "targets": [
        { "expr": "chat_postgres_replica_lag", "legendFormat": "{{replica}}" }
      ]
    }
```

> Timer는 Prometheus에서 `_seconds_bucket`/`_seconds_count`/`_seconds_sum`으로 노출된다. p95는 `histogram_quantile`로 계산한다.

- [ ] **Step 2: dashboard 로딩 확인**

Run: `docker compose restart grafana`
Run: `curl -s -u admin:${GRAFANA_ADMIN_PASSWORD:-admin} 'http://localhost:3000/api/dashboards/uid/chat-release-gate' | grep -c 'WebSocket write latency p95'`
Expected: 1

- [ ] **Step 3: Commit**

```bash
git add infra/grafana/dashboards/chat-release-gate.json
git commit -m "feat: add gateway and replica panels to release gate dashboard"
```

---

## Self-Review

**Spec coverage** (`docs/superpowers/specs/2026-06-11-...` Phase 8.1 항목 대조):
- micrometer-registry-prometheus + actuator prometheus 노출 → Task 1 ✓
- Prometheus + Grafana 컨테이너 + scrape + alert rule 연결 → Task 2 ✓
- Gateway/Fan-out 실측 metric(active connections, room subscription, outbound bytes, batch frames, local delivery, send queue depth, write latency p95/p99, slow client disconnect) → Task 3(컴포넌트) + Task 4(배선) ✓
- `RoomPolicyWorker.gatewaySendQueueDepth` 실측 provider 연결 → Task 5 ✓
- admin `replicaLagMs` placeholder 실측화 → Task 6(gauge 노출) ✓ / 단, admin 응답 DTO에 이 gauge 값을 직접 주입하는 것은 별도 슬라이스로 남긴다(아래 알려진 한계).
- Grafana dashboard 구성 → Task 2 + Task 7 ✓
- reconnect rate metric → **이 plan 범위에서 제외**. reconnect/ticket 계열은 `observability_metrics.md` 3.1 WebSocket Ticket 섹션 소관이고 `scripts/phase7-reconnect-*.mjs`와 함께 다루는 게 응집도가 높다. Phase 8.1 후속 슬라이스로 분리.

**알려진 한계 / 후속 슬라이스:**
- writer lag / fanout lag signal 연결: Task 5는 gatewaySendQueueDepth만 실측 연결한다. writer/fanout lag는 `chat.redis.stream.group.lag` gauge를 RoomPolicy signal로 합성하는 후속 슬라이스에서 채운다.
- admin `searchP95LatencyMs` 실측: admin search timer는 별도 admin 모듈 작업이라 이 plan에서 제외(Task 6은 replica lag만). admin 응답에 search p95를 주입하려면 admin search latency timer를 먼저 둔다.

**Placeholder scan:** TBD/TODO/모호 지시 없음. 모든 코드 step에 실제 코드 포함.

**Type consistency:** `WebSocketGatewayMetrics`의 메서드 시그니처(`recordLocalDelivery(Int)`, `recordWriteLatency(Long, String)`, `registerGauges(() -> Number x3)`)가 Task 3 정의와 Task 4 호출에서 일치. `RoomPolicySignals(gatewaySendQueueDepth = ...)`가 기존 data class 필드명과 일치. `ReadReplicaLagPolicy.currentLagMillis()`가 Task 6 내부에서 일관 사용.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-25-phase8-1-observability-pipeline-gateway-metrics.md`.
