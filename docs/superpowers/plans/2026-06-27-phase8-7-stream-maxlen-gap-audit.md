# Phase 8.7 Stream MAXLEN Gap Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded Redis Streams append with `MAXLEN` and scheduled canonical `room_seq` gap audit metrics, while keeping WebSocket heartbeat out of this PR.

**Architecture:** Redis append uses low-level `RedisConnection.streamCommands().xAdd(..., XAddOptions.maxlen(...))` only when `chat.redis.streams.max-len > 0`; otherwise it keeps the existing `StreamOperations.add(key, map)` path. Gap audit is a worker-side scheduled poll that scans recent canonical `chat_messages` rows through `JdbcTemplate`, maps aggregate results, and updates low-cardinality Micrometer gauges.

**Tech Stack:** Kotlin, Spring Boot configuration properties, Spring Data Redis, Spring JDBC, Micrometer, JUnit 5, Mockito.

---

## Files

- Modify: `chat-persistence/src/main/kotlin/config/ChatRedisProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/config/ChatWorkerProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/redis/RedisMessageStreamProducer.kt`
- Create: `chat-persistence/src/main/kotlin/repository/RoomSeqGapAuditRepository.kt`
- Create: `chat-persistence/src/main/kotlin/service/RoomSeqGapAuditMetrics.kt`
- Create: `chat-persistence/src/main/kotlin/service/RoomSeqGapAuditWorker.kt`
- Modify: `chat-worker-application/src/main/kotlin/com/chat/worker/application/MessageWorkerScheduler.kt`
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Modify: `docs/configuration.md`
- Modify: `docs/infrastructure.md`
- Modify: `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`
- Test: `chat-persistence/src/test/kotlin/redis/RedisMessageStreamProducerTest.kt`
- Test: `chat-persistence/src/test/kotlin/repository/RoomSeqGapAuditRepositoryTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/RoomSeqGapAuditMetricsTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/RoomSeqGapAuditWorkerTest.kt`
- Test: `chat-worker-application/src/test/kotlin/com/chat/worker/application/MessageWorkerSchedulerTest.kt`

## Tasks

### Task 1: Redis Streams MAXLEN

- [x] Write failing tests in `RedisMessageStreamProducerTest` proving positive `maxLen` uses `XAddOptions.maxlen`, approximate flag is passed, `maxLen <= 0` uses the existing unbounded append path, and known stream set registration remains intact.
- [x] Run `./gradlew :chat-persistence:test --tests com.chat.persistence.redis.RedisMessageStreamProducerTest --no-daemon` and verify the new tests fail because `ChatRedisProperties.Streams.maxLen` and low-level bounded append do not exist yet.
- [x] Add `maxLen` and `maxLenApproximate` to `ChatRedisProperties.Streams`.
- [x] Update `RedisMessageStreamProducer.append()` so positive `maxLen` serializes the stream key and fields as UTF-8 bytes, calls `xAdd` with `XAddOptions.maxlen(maxLen).approximateTrimming(maxLenApproximate)`, and throws if Redis returns null.
- [x] Re-run the Redis producer test and verify it passes.

### Task 2: Gap Audit Repository

- [x] Write failing tests in `RoomSeqGapAuditRepositoryTest` proving the SQL uses `lag(room_seq)`, binds a `Timestamp` cutoff, maps all four aggregate fields, and returns zeros when the query returns no row.
- [x] Run `./gradlew :chat-persistence:test --tests com.chat.persistence.repository.RoomSeqGapAuditRepositoryTest --no-daemon` and verify failure because the repository does not exist.
- [x] Create `RoomSeqGapAuditSummary` and `RoomSeqGapAuditRepository`.
- [x] Implement `auditSince(cutoff: Instant)` with one aggregate SQL query against `chat_messages`.
- [x] Re-run the repository test and verify it passes.

### Task 3: Gap Audit Metrics

- [x] Write failing tests in `RoomSeqGapAuditMetricsTest` proving the four gauges are registered without tags and repeated updates replace gauge values.
- [x] Run `./gradlew :chat-persistence:test --tests com.chat.persistence.service.RoomSeqGapAuditMetricsTest --no-daemon` and verify failure because the metric class does not exist.
- [x] Create `RoomSeqGapAuditMetrics` with the existing `ObjectProvider<MeterRegistry>?` and `Noop` pattern.
- [x] Re-run the metrics test and verify it passes.

### Task 4: Gap Audit Worker

- [x] Write failing tests in `RoomSeqGapAuditWorkerTest` proving enabled workers query `now - lookback`, update metrics, and swallow repository exceptions.
- [x] Run `./gradlew :chat-persistence:test --tests com.chat.persistence.service.RoomSeqGapAuditWorkerTest --no-daemon` and verify failure because the worker does not exist.
- [x] Add `RoomSeqGapAudit` settings to `ChatWorkerProperties`.
- [x] Create `RoomSeqGapAuditWorker` with injected `Clock`, repository, metrics, and properties.
- [x] Re-run the worker test and verify it passes.

### Task 5: Scheduler Wiring

- [x] Write failing tests in `MessageWorkerSchedulerTest` proving `pollRoomSeqGapAudit()` calls the worker when enabled and skips it when disabled.
- [x] Run `./gradlew :chat-worker-application:test --tests com.chat.worker.application.MessageWorkerSchedulerTest --no-daemon` and verify failure because scheduler wiring does not exist.
- [x] Add `RoomSeqGapAuditWorker` to `MessageWorkerScheduler` constructor and scheduled method.
- [x] Re-run the scheduler test and verify it passes.

### Task 6: Configuration and Docs

- [x] Add `CHAT_REDIS_STREAMS_MAX_LEN`, `CHAT_REDIS_STREAMS_MAX_LEN_APPROXIMATE`, `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_ENABLED`, `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_POLL_DELAY_MILLIS`, and `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_LOOKBACK` to `application-docker.yml`.
- [x] Update `docs/configuration.md` and `docs/infrastructure.md` with the new behavior and heartbeat split.
- [x] Update the high-traffic design spec Phase 8.7 status to show MAXLEN/gap audit in this PR and heartbeat in the next PR.
- [x] Run `./gradlew :chat-runtime-config:processResources --no-daemon` and verify configuration syntax.

### Task 7: Full Verification and Commit

- [x] Run `./gradlew :chat-persistence:test --tests com.chat.persistence.redis.RedisMessageStreamProducerTest --tests com.chat.persistence.repository.RoomSeqGapAuditRepositoryTest --tests com.chat.persistence.service.RoomSeqGapAuditMetricsTest --tests com.chat.persistence.service.RoomSeqGapAuditWorkerTest --no-daemon`.
- [x] Run `./gradlew :chat-worker-application:test --tests com.chat.worker.application.MessageWorkerSchedulerTest --no-daemon`.
- [x] Run `./gradlew test --no-daemon`.
- [x] Run `git diff --check`.
- [x] Commit with `feat: add stream maxlen gap audit`.
