# Phase 7 WebSocket Reconnect Synthetic Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Phase 7 synthetic reconnect runner that measures normal WebSocket one-time ticket issue and handshake success as release gates.

**Architecture:** Keep pure option parsing, attempt planning, and summary gate calculation in `scripts/lib/phase7ReconnectLoadPlan.mjs`. Keep network work in `scripts/phase7-reconnect-load.mjs`, following the existing Node ESM smoke script style.

**Tech Stack:** Node.js ESM scripts, `node:test`, raw TCP/TLS WebSocket handshake, Markdown docs.

---

## Tasks

### Task 1: Reconnect summary contract tests

**Files:**
- Create: `scripts/lib/phase7ReconnectLoadPlan.test.mjs`
- Create later: `scripts/lib/phase7ReconnectLoadPlan.mjs`

- [ ] **Step 1: Write failing tests**

Cover CLI parsing, enum validation, reconnect attempt planning, success summary, rate-limit failure summary, handshake failure summary, and bounded ticket failure classification.

- [ ] **Step 2: Run red test**

```bash
node --test scripts/lib/phase7ReconnectLoadPlan.test.mjs
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` because `phase7ReconnectLoadPlan.mjs` does not exist yet.

### Task 2: Pure reconnect plan helper

**Files:**
- Create: `scripts/lib/phase7ReconnectLoadPlan.mjs`
- Test: `scripts/lib/phase7ReconnectLoadPlan.test.mjs`

- [ ] **Step 1: Implement parser and validators**

Add `parseReconnectLoadArgs(argv, env)` with bounded `cohort` and `reason` enums, positive integer validation, and ratio validation.

- [ ] **Step 2: Implement summary helpers**

Add `buildReconnectAttemptPlan()`, `summarizeReconnectAttempts()`, `classifyTicketIssueFailure()`, and `exitCodeForReconnectSummary()`.

- [ ] **Step 3: Run green test**

```bash
node --test scripts/lib/phase7ReconnectLoadPlan.test.mjs
```

Expected: PASS.

### Task 3: Executable reconnect runner

**Files:**
- Create: `scripts/phase7-reconnect-load.mjs`

- [ ] **Step 1: Add network script**

Use existing HTTP URL conventions:

```text
CHAT_HTTP_URL=http://localhost/api
CHAT_WS_URL=ws://localhost/api/ws/chat
CHAT_PHASE7_RECONNECT_TIMEOUT_MS=15000
```

The script registers clients, logs them in, issues one fresh WebSocket ticket per attempt, performs raw WebSocket handshake, closes the socket, then prints summary JSON.

- [ ] **Step 2: Run syntax check**

```bash
node --check scripts/phase7-reconnect-load.mjs
```

Expected: PASS.

### Task 4: Documentation and slice index

**Files:**
- Create: `docs/superpowers/specs/2026-06-24-phase7-websocket-reconnect-load-test-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-websocket-reconnect-load-test.md`
- Modify: `docs/phase7_reconnect_load_test_scenarios.md`
- Modify: `docs/phase7_slices.md`

- [ ] **Step 1: Write design and implementation plan**

Document conditions, goals, non-goals, summary gates, complexity, caveats, alternatives, and follow-up questions.

- [ ] **Step 2: Update operator scenario doc**

Add the runnable command and actual JSON summary fields.

- [ ] **Step 3: Update slice index**

Add the implemented reconnect synthetic load test slice and remove completed duplicate candidates from the next-candidate list.

### Task 5: Verification

**Files:**
- All modified files

- [ ] **Step 1: Run focused tests**

```bash
node --test scripts/lib/phase7ReconnectLoadPlan.test.mjs
```

Expected: PASS.

- [ ] **Step 2: Run all Node script tests**

```bash
node --test scripts/lib/*.test.mjs
```

Expected: PASS.

- [ ] **Step 3: Run syntax checks**

```bash
node --check scripts/phase7-reconnect-load.mjs
```

Expected: PASS.

- [ ] **Step 4: Run whitespace check**

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Optional runtime smoke**

Run only when local Compose services are already healthy:

```bash
node scripts/phase7-reconnect-load.mjs \
  --scenario baseline-reconnect \
  --clients 2 \
  --reconnects-per-client 2 \
  --cohort synthetic \
  --reason network_flap
```

Expected: final JSON includes `ok=true` when all ticket issue and handshake attempts succeed.
