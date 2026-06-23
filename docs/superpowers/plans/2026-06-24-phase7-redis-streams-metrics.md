# Phase 7 Redis Streams Metrics 구현 계획

> **For agentic workers:** 이 계획은 Redis Streams 처리 경로의 metric contract를 구현한다. 작업은 테스트 우선으로 진행하고, 완료 후 `docs/observability_metrics.md`와 `docs/phase7_slices.md`를 실제 metric 이름에 맞게 갱신한다.

**Goal:** Redis Streams append, read/claim, writer/fanout worker 처리, dead-letter 이동을 bounded tag metric으로 관측 가능하게 만든다.

**Architecture:** `MessageStreamMetrics`를 공통 Micrometer adapter로 두고 producer, consumer, writer worker, fanout worker에 주입한다. metric tag에는 shard/group/source/role/outcome만 사용한다.

**Tech Stack:** Kotlin, Spring Boot, Micrometer, JUnit 5, SimpleMeterRegistry.

## Tasks

### Task 1: Metric Contract Tests

**Files:**

- Create: `chat-persistence/src/test/kotlin/service/MessageStreamMetricsTest.kt`
- Modify: `chat-persistence/src/test/kotlin/redis/RedisMessageStreamProducerTest.kt`
- Modify: `chat-persistence/src/test/kotlin/service/MessageWriterWorkerTest.kt`
- Modify: `chat-persistence/src/test/kotlin/service/HotRoomFanoutWorkerTest.kt`

- [x] **Step 1: Write failing tests**

Cover append latency tags, consumer record counter tags, worker batch latency, worker record counter, and absence of raw stream keys in tag values.

- [x] **Step 2: Run red test**

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.service.MessageStreamMetricsTest \
  --tests com.chat.persistence.service.MessageWriterWorkerTest \
  --tests com.chat.persistence.service.HotRoomFanoutWorkerTest \
  --tests com.chat.persistence.redis.RedisMessageStreamProducerTest \
  --no-daemon
```

Expected: FAIL before implementation because `MessageStreamMetrics` and constructor injection do not exist.

### Task 2: Common Metrics Adapter

**Files:**

- Create: `chat-persistence/src/main/kotlin/service/MessageStreamMetrics.kt`

- [x] **Step 1: Implement Micrometer adapter**

Add methods for append latency, consumer records, worker batch latency/records, and dead-letter counters.

- [x] **Step 2: Keep no-op default safe**

Provide a no-op default instance so plain unit tests and fallback constructors do not require a registry.

### Task 3: Redis Streams Path Instrumentation

**Files:**

- Modify: `chat-persistence/src/main/kotlin/redis/RedisMessageStreamProducer.kt`
- Modify: `chat-persistence/src/main/kotlin/redis/RedisMessageStreamConsumer.kt`

- [x] **Step 1: Instrument append**

Record `chat.redis.stream.append.latency` on success and failure.

- [x] **Step 2: Instrument read/claim**

Record `new`, `pending_scanned`, and `pending_claimed` consumer record counters with bounded shard/group/source tags.

### Task 4: Worker Instrumentation

**Files:**

- Modify: `chat-persistence/src/main/kotlin/service/MessageWriterWorker.kt`
- Modify: `chat-persistence/src/main/kotlin/service/HotRoomFanoutWorker.kt`

- [x] **Step 1: Instrument writer worker**

Record success, partial, failure batch latency and processed record counts. Record dead-letter movement after max delivery is exceeded.

- [x] **Step 2: Instrument fanout worker**

Record success, failure, lease-lost batch latency and processed record counts. Record dead-letter movement after max delivery is exceeded.

### Task 5: Documentation and Verification

**Files:**

- Create: `docs/superpowers/specs/2026-06-24-phase7-redis-streams-metrics-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-redis-streams-metrics.md`
- Modify: `docs/observability_metrics.md`
- Modify: `docs/phase7_slices.md`

- [x] **Step 1: Update docs**

Document metric names, tag contracts, complexity, caveats, alternatives, and next slice candidates.

- [x] **Step 2: Run focused tests**

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.service.MessageStreamMetricsTest \
  --tests com.chat.persistence.service.MessageWriterWorkerTest \
  --tests com.chat.persistence.service.HotRoomFanoutWorkerTest \
  --tests com.chat.persistence.redis.RedisMessageStreamProducerTest \
  --no-daemon
```

Expected: PASS.

- [x] **Step 3: Run module tests**

```bash
./gradlew :chat-persistence:test --no-daemon
```

Expected: PASS.

- [x] **Step 4: Run whitespace check**

```bash
git diff --check
```

Expected: no output.
