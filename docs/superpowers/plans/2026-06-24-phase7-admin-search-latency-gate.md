# Phase 7 Admin Search Latency Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add warm p95 and cold p99 release gate semantics to the admin search measurement runner.

**Architecture:** Keep HTTP sampling in `scripts/measure-admin-search-p95.mjs`. Keep pure gate phase and summary calculation in `scripts/lib/adminMeasurePlan.mjs` and `scripts/lib/adminLatencyStats.mjs` so the gate contract is testable without a running service.

**Tech Stack:** Node.js ESM scripts, `node:test`, Markdown docs.

---

## Tasks

### Task 1: Gate Contract Tests

**Files:**

- Modify: `scripts/lib/adminMeasurePlan.test.mjs`
- Modify: `scripts/lib/adminLatencyStats.test.mjs`

- [x] **Step 1: Write failing tests**

Add tests for `buildGatePhases()`, `summarizeGateSamples()`, and `summarizeGateReport()`.

- [x] **Step 2: Run red test**

```bash
node --test scripts/lib/adminMeasurePlan.test.mjs scripts/lib/adminLatencyStats.test.mjs
```

Expected before implementation: FAIL with missing exports for the new gate helpers.

### Task 2: Pure Gate Helpers

**Files:**

- Modify: `scripts/lib/adminMeasurePlan.mjs`
- Modify: `scripts/lib/adminLatencyStats.mjs`

- [x] **Step 1: Implement gate phase helper**

Add `buildGatePhases(gate)` with `warm`, `cold`, and cold-first `both` support.

- [x] **Step 2: Implement gate summary helpers**

Add `summarizeGateSamples(samples, { gate, targetMs })` and `summarizeGateReport(results)`.

- [x] **Step 3: Run green test**

```bash
node --test scripts/lib/adminMeasurePlan.test.mjs scripts/lib/adminLatencyStats.test.mjs
```

Expected: PASS.

### Task 3: Measurement Runner Wiring

**Files:**

- Modify: `scripts/measure-admin-search-p95.mjs`

- [x] **Step 1: Add CLI options**

Add `--gate warm|cold|both`, `--target-warm-p95-ms`, and `--target-cold-p99-ms`. Keep `--target-p95-ms` as a warm threshold alias.

- [x] **Step 2: Separate cold and warm execution**

Cold phase runs samples without warmup. Warm phase runs configured warmup before samples.

- [x] **Step 3: Add top-level gate report fields**

Add `ok`, `failedGates`, `targetWarmP95Ms`, `targetColdP99Ms`, and per-result `gateResults`.

- [x] **Step 4: Run syntax check**

```bash
node --check scripts/measure-admin-search-p95.mjs
```

Expected: PASS.

### Task 4: Documentation

**Files:**

- Create: `docs/phase7_admin_search_latency_gate.md`
- Create: `docs/superpowers/specs/2026-06-24-phase7-admin-search-latency-gate-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-admin-search-latency-gate.md`
- Modify: `docs/observability_metrics.md`
- Modify: `docs/phase7_slices.md`
- Modify: `docs/infrastructure.md`

- [x] **Step 1: Document operator commands**

Add warm, cold, and combined gate commands with summary fields and caveats.

- [x] **Step 2: Update Phase 7 tracking docs**

Add slice 6 and move remaining candidates forward.

### Task 5: Verification

**Files:**

- All modified files

- [x] **Step 1: Run focused tests**

```bash
node --test scripts/lib/adminMeasurePlan.test.mjs scripts/lib/adminLatencyStats.test.mjs
node --check scripts/measure-admin-search-p95.mjs
```

Expected: PASS.

- [x] **Step 2: Run all Node script tests**

```bash
node --test scripts/lib/*.test.mjs
```

Expected: PASS.

- [x] **Step 3: Run whitespace check**

```bash
git diff --check
```

Expected: no output.
