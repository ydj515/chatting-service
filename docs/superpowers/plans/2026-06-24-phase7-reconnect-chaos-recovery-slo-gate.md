# Phase 7 Reconnect Chaos Recovery SLO Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a release-blocking wall-clock recovery SLO gate to the Phase 7 reconnect chaos orchestrator.

**Architecture:** Keep option parsing and summary evaluation in `scripts/lib/phase7ReconnectChaosPlan.mjs`. Measure elapsed time in `scripts/phase7-reconnect-chaos.mjs` from the first fault injection to reconnect-load child completion, then merge that value into the existing summary.

**Tech Stack:** Node.js ESM scripts, `node:test`, Markdown docs.

---

## Tasks

### Task 1: Recovery SLO Contract Tests

**Files:**

- Modify: `scripts/lib/phase7ReconnectChaosPlan.test.mjs`

- [x] **Step 1: Write failing tests**

Add tests for default `maxRecoverySloMs`, `--max-recovery-slo-ms`, invalid values, summary success, SLO exceeded failure, and dry-run null elapsed time.

- [x] **Step 2: Verify RED**

```bash
node --test scripts/lib/phase7ReconnectChaosPlan.test.mjs
```

Expected before implementation: FAIL because `maxRecoverySloMs`, `recoveryElapsedMs`, and `recoverySloMet` are missing.

### Task 2: Pure Logic Implementation

**Files:**

- Modify: `scripts/lib/phase7ReconnectChaosPlan.mjs`

- [x] **Step 1: Parse SLO option**

Add `maxRecoverySloMs` to fault defaults and parse `--max-recovery-slo-ms` as a positive integer.

- [x] **Step 2: Merge SLO into summary**

Compute `recoverySloMet`, add `recovery_slo_ms` to failed gates when exceeded, and make release blocking depend on both reconnect gate and SLO gate.

- [x] **Step 3: Verify GREEN**

```bash
node --test scripts/lib/phase7ReconnectChaosPlan.test.mjs
```

Expected: PASS.

### Task 3: Runner Timing

**Files:**

- Modify: `scripts/phase7-reconnect-chaos.mjs`

- [x] **Step 1: Carry dry-run SLO metadata**

Pass `maxRecoverySloMs` and null `recoveryElapsedMs` into dry-run summaries.

- [x] **Step 2: Measure execution elapsed time**

Record `Date.now()` at first fault injection and compute `recoveryElapsedMs` after reconnect-load child exit.

### Task 4: Documentation

**Files:**

- Create: `docs/phase7_reconnect_chaos_recovery_slo_gate.md`
- Create: `docs/superpowers/specs/2026-06-24-phase7-reconnect-chaos-recovery-slo-gate-design.md`
- Create: `docs/superpowers/plans/2026-06-24-phase7-reconnect-chaos-recovery-slo-gate.md`
- Modify: `docs/phase7_reconnect_chaos_orchestration.md`
- Modify: `docs/phase7_slices.md`

- [x] **Step 1: Document CLI and summary contract**

Describe `--max-recovery-slo-ms`, `recoveryElapsedMs`, `maxRecoverySloMs`, `recoverySloMet`, and `recovery_slo_ms`.

- [x] **Step 2: Update slice index**

Move Reconnect chaos recovery SLO gate from candidate to implemented slice 12.

### Task 5: Verification

**Files:**

- All modified files

- [x] **Step 1: Run focused tests**

```bash
node --test scripts/lib/phase7ReconnectChaosPlan.test.mjs
node --check scripts/lib/phase7ReconnectChaosPlan.mjs
node --check scripts/phase7-reconnect-chaos.mjs
```

Expected: PASS.

- [x] **Step 2: Run dry-run smoke**

```bash
node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart
node scripts/phase7-reconnect-chaos.mjs --fault gateway-hard-kill --max-recovery-slo-ms 12000
```

Expected: exit `0`, summary includes recovery SLO fields.

- [x] **Step 3: Run full regression**

```bash
node --test scripts/lib/*.test.mjs scripts/*.test.mjs
./gradlew test --no-daemon
git diff --check
```

Expected: PASS / no diff-check output.
