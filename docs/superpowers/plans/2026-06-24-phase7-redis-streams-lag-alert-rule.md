# Phase 7 Redis Streams Lag Alert Rule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Prometheus alert rule artifacts for Redis Streams direct lag and pending gauges.

**Architecture:** Keep alert rule definitions in `scripts/lib/phase7RedisStreamsAlertRules.mjs`, render the Prometheus YAML artifact at `infra/prometheus/rules/phase7-redis-streams-lag.rules.yml`, and test that the file stays in sync with the renderer.

**Tech Stack:** Node.js ESM scripts, `node:test`, Prometheus alert rule YAML, Markdown docs.

---

## Tasks

### Task 1: Alert Rule Contract Tests

**Files:**

- Create: `scripts/lib/phase7RedisStreamsAlertRules.test.mjs`

- [x] **Step 1: Write failing tests**

Cover warning/critical alert names, threshold expressions, evaluation windows, bounded labels, and YAML file sync.

- [x] **Step 2: Run red test**

```bash
node --test scripts/lib/phase7RedisStreamsAlertRules.test.mjs
```

Expected before implementation: FAIL with missing `phase7RedisStreamsAlertRules.mjs`.

### Task 2: Alert Rule Renderer and Artifact

**Files:**

- Create: `scripts/lib/phase7RedisStreamsAlertRules.mjs`
- Create: `infra/prometheus/rules/phase7-redis-streams-lag.rules.yml`

- [x] **Step 1: Add rule contract**

Define lag/pending sustained warning and critical alerts.

- [x] **Step 2: Render YAML artifact**

Render labels and annotations with bounded `consumer_group`, `stream_shard` context.

- [x] **Step 3: Run green test**

```bash
node --test scripts/lib/phase7RedisStreamsAlertRules.test.mjs
```

Expected: PASS.

### Task 3: Documentation

**Files:**

- Create: `docs/phase7_redis_streams_lag_alert_rule.md`
- Create: `docs/superpowers/specs/2026-06-24-phase7-redis-streams-lag-alert-rule-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-redis-streams-lag-alert-rule.md`
- Modify: `docs/phase7_redis_streams_direct_lag_gauge.md`
- Modify: `docs/observability_metrics.md`
- Modify: `docs/phase7_slices.md`
- Modify: `docs/infrastructure.md`

- [x] **Step 1: Document thresholds and windows**

Document warning/critical expressions, caveats, and operational response.

- [x] **Step 2: Update Phase 7 tracking docs**

Add slice 11 and move remaining candidates forward.

### Task 4: Verification

**Files:**

- All modified files

- [x] **Step 1: Run focused tests**

```bash
node --test scripts/lib/phase7RedisStreamsAlertRules.test.mjs
node --check scripts/lib/phase7RedisStreamsAlertRules.mjs
```

Expected: PASS.

- [x] **Step 2: Run all Node script tests**

```bash
node --test scripts/lib/*.test.mjs scripts/*.test.mjs
```

Expected: PASS.

- [x] **Step 3: Run full test suite**

```bash
./gradlew test --no-daemon
```

Expected: PASS.

- [x] **Step 4: Run whitespace check**

```bash
git diff --check
```

Expected: no output.
