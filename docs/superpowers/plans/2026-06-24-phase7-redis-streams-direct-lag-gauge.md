# Phase 7 Redis Streams Direct Lag Gauge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose Redis Streams consumer group lag and pending counts as bounded Micrometer gauges.

**Architecture:** `SpringRedisStreamLagReader` reads `XINFO GROUPS` and `XPENDING` summary from Redis. `RedisStreamLagMonitor` aggregates snapshots by stream shard and consumer group. `MessageStreamMetrics` owns Micrometer gauge registration and state updates.

**Tech Stack:** Kotlin, Spring Boot scheduling, Spring Data Redis Streams, Micrometer, JUnit 5, Mockito.

---

## Tasks

### Task 1: Reader and Monitor Tests

**Files:**

- Create: `chat-persistence/src/test/kotlin/redis/RedisStreamLagReaderTest.kt`
- Create: `chat-persistence/src/test/kotlin/service/RedisStreamLagMonitorTest.kt`

- [x] **Step 1: Write failing tests**

Cover `XINFO GROUPS` lag, `XPENDING` summary pending, shard parsing, shard/group aggregation, and absence of raw stream key tags.

- [x] **Step 2: Run red test**

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.redis.RedisStreamLagReaderTest \
  --tests com.chat.persistence.service.RedisStreamLagMonitorTest \
  --no-daemon
```

Expected before implementation: FAIL with missing direct lag reader and monitor types.

### Task 2: Direct Lag Reader

**Files:**

- Create: `chat-persistence/src/main/kotlin/redis/RedisStreamLagReader.kt`

- [x] **Step 1: Implement snapshot contract**

Add `RedisStreamGroupLagSnapshot` and `RedisStreamLagReader`.

- [x] **Step 2: Implement Spring Redis reader**

Read known stream keys, `XINFO GROUPS` lag, and `XPENDING` summary pending count.

### Task 3: Gauge State and Monitor

**Files:**

- Modify: `chat-persistence/src/main/kotlin/service/MessageStreamMetrics.kt`
- Create: `chat-persistence/src/main/kotlin/service/RedisStreamLagMonitor.kt`

- [x] **Step 1: Add gauge update methods**

Add `chat.redis.stream.group.lag` and `chat.redis.stream.group.pending` gauge state keyed by registry, metric name, stream shard, and consumer group.

- [x] **Step 2: Aggregate snapshots**

Aggregate stream snapshots by `stream_shard` and `consumer_group` before updating gauges.

### Task 4: Worker Scheduler Integration

**Files:**

- Modify: `chat-persistence/src/main/kotlin/config/ChatWorkerProperties.kt`
- Modify: `chat-worker-application/src/main/kotlin/com/chat/worker/application/MessageWorkerScheduler.kt`
- Modify: `chat-worker-application/src/test/kotlin/com/chat/worker/application/MessageWorkerSchedulerTest.kt`
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`

- [x] **Step 1: Add properties**

Add `chat.worker.redis-stream-lag.enabled` and `chat.worker.redis-stream-lag.poll-delay-millis`.

- [x] **Step 2: Schedule monitor polling**

Add a scheduled method that calls `RedisStreamLagMonitor.poll()` when enabled.

### Task 5: Documentation and Verification

**Files:**

- Create: `docs/phase7_redis_streams_direct_lag_gauge.md`
- Create: `docs/superpowers/specs/2026-06-24-phase7-redis-streams-direct-lag-gauge-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-redis-streams-direct-lag-gauge.md`
- Modify: `docs/observability_metrics.md`
- Modify: `docs/phase7_slices.md`
- Modify: `docs/infrastructure.md`

- [x] **Step 1: Update docs**

Document metric names, tags, settings, complexity, caveats, and alternatives.

- [x] **Step 2: Run focused tests**

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.redis.RedisStreamLagReaderTest \
  --tests com.chat.persistence.service.RedisStreamLagMonitorTest \
  :chat-worker-application:test \
  --tests com.chat.worker.application.MessageWorkerSchedulerTest \
  --no-daemon
```

Expected: PASS.

- [x] **Step 3: Run full tests**

```bash
./gradlew test --no-daemon
```

Expected: PASS.

- [x] **Step 4: Run whitespace check**

```bash
git diff --check
```

Expected: no output.
