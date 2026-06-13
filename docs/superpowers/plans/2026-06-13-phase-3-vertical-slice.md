# Phase 3 Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the first Phase 3 slice where accepted chat messages are appended to Redis Streams before compatibility JPA persistence and fan-out.

**Architecture:** Keep the existing synchronous JPA compatibility save and Pub/Sub fan-out for this slice, but add a Redis Streams producer and message envelope that becomes the durable ingest contract. `ChatServiceImpl.sendMessage()` must fail before persistence when Redis Streams append fails.

**Tech Stack:** Kotlin, Spring Boot, Spring Data Redis Streams, JPA, Mockito/JUnit 5.

---

### Task 1: Redis Streams Append Gate

**Files:**
- Create: `chat-persistence/src/main/kotlin/redis/MessageStreamEnvelope.kt`
- Create: `chat-persistence/src/main/kotlin/redis/MessageStreamProducer.kt`
- Create: `chat-persistence/src/main/kotlin/redis/RedisMessageStreamProducer.kt`
- Modify: `chat-persistence/src/main/kotlin/config/ChatRedisProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`
- Test: `chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt`
- Test: `chat-persistence/src/test/kotlin/redis/RedisMessageStreamProducerTest.kt`

- [x] **Step 1: Write failing tests**

Add tests proving that `sendMessage()` appends a Phase 3 envelope before compatibility save, and that append failure prevents persistence.

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :chat-persistence:test --tests '*ChatServiceImplMessageContractTest' --no-daemon
```

Expected: fails because `RedisMessageStreamProducer` and `MessageStreamEnvelope` do not exist yet.

- [x] **Step 3: Implement minimal producer and envelope**

Add a Redis Streams producer using `RedisTemplate.opsForStream().add(streamKey, fields)`.

- [x] **Step 4: Wire ChatService append gate**

Call the producer after sequence/shard assignment and before `MessagePersistenceService.save()`.

- [x] **Step 5: Verify GREEN**

Run:

```bash
./gradlew :chat-persistence:test --tests '*ChatServiceImplMessageContractTest' --no-daemon
```

Expected: test passes.

### Task 2: Phase 3 Slice Documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`

- [x] **Step 1: Document slice status**

Record that the first Phase 3 slice implements Streams append gate while keeping compatibility JPA save and existing fan-out synchronous.

- [x] **Step 2: Verify docs**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.
