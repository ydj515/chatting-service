# Phase 7 Admin Search Slow Query Plan Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` artifacts when the admin search cold p99 gate fails.

**Architecture:** Add `scripts/lib/adminSearchPlanCapture.mjs` for capture target selection, SQL generation, artifact path generation, and psql execution. Wire it into `scripts/measure-admin-search-p95.mjs` behind `--slow-query-plan on-cold-failure`.

**Tech Stack:** Node.js ESM scripts, `node:test`, PostgreSQL psql, Markdown docs.

---

## Tasks

### Task 1: Capture Contract Tests

**Files:**

- Create: `scripts/lib/adminSearchPlanCapture.test.mjs`
- Modify: `scripts/measure-admin-search-p95.test.mjs`

- [x] **Step 1: Write failing tests**

Cover failed cold gate target selection, FTS/history EXPLAIN SQL generation, literal escaping, and invalid CLI capture mode.

- [x] **Step 2: Run red test**

```bash
node --test scripts/lib/adminSearchPlanCapture.test.mjs scripts/measure-admin-search-p95.test.mjs
```

Expected before implementation: FAIL with missing `adminSearchPlanCapture.mjs` and missing CLI validation.

### Task 2: Capture Helper Implementation

**Files:**

- Create: `scripts/lib/adminSearchPlanCapture.mjs`

- [x] **Step 1: Add capture mode parser**

Support `off` and `on-cold-failure`.

- [x] **Step 2: Add target selection**

Select only `cold` gate results with `summary.passedTarget === false`.

- [x] **Step 3: Add EXPLAIN SQL builders**

Generate app-equivalent history and search SQL with bounded filters, ordering, limit, and escaped literals.

- [x] **Step 4: Add artifact writer**

Run psql with a bounded timeout, parse JSON output when possible, and write metadata plus raw plan output.

### Task 3: Runner Wiring

**Files:**

- Modify: `scripts/measure-admin-search-p95.mjs`

- [x] **Step 1: Add CLI options**

Add `--slow-query-plan`, `--slow-query-plan-output-dir`, `--slow-query-plan-timeout-ms`, `--psql-mode`, `--psql-service`, and DB connection overrides.

- [x] **Step 2: Attach capture results to report**

Add `options.slowQueryPlan`, `options.slowQueryPlanOutputDir`, `options.slowQueryPlanTimeoutMs`, `options.psql`, and top-level `slowQueryPlans`.

### Task 4: Documentation

**Files:**

- Create: `docs/phase7_admin_search_slow_query_plan_capture.md`
- Create: `docs/superpowers/specs/2026-06-24-phase7-admin-search-slow-query-plan-capture-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-admin-search-slow-query-plan-capture.md`
- Modify: `docs/phase7_admin_search_latency_gate.md`
- Modify: `docs/phase7_slices.md`
- Modify: `docs/observability_metrics.md`
- Modify: `docs/infrastructure.md`

- [x] **Step 1: Document operator command and JSON contract**

Add cold gate command with `--slow-query-plan on-cold-failure` and explain artifact fields.

- [x] **Step 2: Update Phase 7 tracking docs**

Add slice 10 and move remaining candidates forward.

### Task 5: Verification

**Files:**

- All modified files

- [x] **Step 1: Run focused tests**

```bash
node --test scripts/lib/adminSearchPlanCapture.test.mjs scripts/measure-admin-search-p95.test.mjs
node --check scripts/measure-admin-search-p95.mjs
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
