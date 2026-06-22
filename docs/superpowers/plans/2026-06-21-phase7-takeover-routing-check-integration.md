# Phase 7 Takeover Routing Check Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** `phase6-fanout-takeover-smoke.mjs` restore 이후 Phase 7 Nginx role routing check를 opt-in으로 실행하게 연결한다.

**Architecture:** CLI parsing and child-process argument construction stay in `scripts/lib/phase6TakeoverSmokePlan.mjs` for unit testing. The executable smoke script only restores the worker scale, then conditionally invokes `scripts/phase7-role-routing-check.mjs` when `--verify-routing-after-restore` is set.

**Tech Stack:** Node.js ESM, `node:test`, `node:assert/strict`, `child_process.execFileSync`.

---

## File Structure

- Modify: `scripts/lib/phase6TakeoverSmokePlan.test.mjs`
  - Add tests for opt-in defaults, CLI option parsing, and routing check argument generation.
- Modify: `scripts/lib/phase6TakeoverSmokePlan.mjs`
  - Add routing check options to `parseTakeoverSmokeArgs`.
  - Add `buildRoutingCheckArgs(options)`.
- Modify: `scripts/phase6-fanout-takeover-smoke.mjs`
  - Import `buildRoutingCheckArgs`.
  - Run `phase7-role-routing-check.mjs` after successful restore only when opted in.
- Modify: `docs/phase7_nginx_role_routing_check.md`
  - Document the takeover smoke opt-in command.
- Modify: `docs/phase7_slices.md`
  - Update slice 2 links and status.

---

### Task 1: RED Tests For Opt-In Arguments

**Files:**
- Modify: `scripts/lib/phase6TakeoverSmokePlan.test.mjs`
- Test: `node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs`

- [x] **Step 1: Write failing tests**

```javascript
import {
  buildLoadChatArgs,
  buildRoutingCheckArgs,
  buildRunCapturedOptions,
  findOwnerContainer,
  parseDockerInspectRows,
  parseLoadChatJson,
  parseTakeoverSmokeArgs,
  parseWorkerContainerIds,
  redisOwnerScanPattern,
} from './phase6TakeoverSmokePlan.mjs';

test('parseTakeoverSmokeArgs keeps routing check disabled by default', () => {
  const options = parseTakeoverSmokeArgs([]);

  assert.equal(options.verifyRoutingAfterRestore, false);
  assert.equal(options.routingCheckBaseUrl, 'http://localhost');
  assert.equal(options.routingCheckAdminToken, 'test');
  assert.equal(options.routingCheckTimeoutMs, 3000);
});

test('parseTakeoverSmokeArgs maps Phase 7 routing check opt-in options', () => {
  const options = parseTakeoverSmokeArgs([
    '--verify-routing-after-restore',
    '--routing-check-base-url',
    'http://localhost:8088',
    '--routing-check-admin-token',
    'secret',
    '--routing-check-timeout-ms',
    '1200',
  ]);

  assert.equal(options.verifyRoutingAfterRestore, true);
  assert.equal(options.routingCheckBaseUrl, 'http://localhost:8088');
  assert.equal(options.routingCheckAdminToken, 'secret');
  assert.equal(options.routingCheckTimeoutMs, 1200);
});

test('buildRoutingCheckArgs maps takeover options to phase7 routing check CLI args', () => {
  assert.deepEqual(
    buildRoutingCheckArgs({
      routingCheckBaseUrl: 'http://localhost:8088',
      routingCheckAdminToken: 'secret',
      routingCheckTimeoutMs: 1200,
    }),
    [
      '--base-url',
      'http://localhost:8088',
      '--admin-token',
      'secret',
      '--timeout-ms',
      '1200',
      '--json',
    ],
  );
});
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
```

Expected: FAIL because `buildRoutingCheckArgs` is not exported and parser does not understand `--verify-routing-after-restore`.

---

### Task 2: GREEN Parser And Builder Implementation

**Files:**
- Modify: `scripts/lib/phase6TakeoverSmokePlan.mjs`
- Test: `node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs`

- [x] **Step 1: Implement parser and builder**

```javascript
export function parseTakeoverSmokeArgs(argv) {
  const options = {
    service: 'chat-worker-app-1',
    restoreScale: 2,
    room: 'phase6-takeover',
    viewers: 3,
    messagesPerSec: 20,
    durationSeconds: 20,
    killAfterSeconds: 5,
    drainWaitSeconds: 12,
    minReceivedRatio: 0.9,
    ownerLeaseKeyPrefix: 'chat:fanout:owner:room:',
    verifyRoutingAfterRestore: false,
    routingCheckBaseUrl: process.env.CHAT_PHASE7_BASE_URL ?? 'http://localhost',
    routingCheckAdminToken: process.env.CHAT_ADMIN_TOKEN ?? 'test',
    routingCheckTimeoutMs: positiveInteger(process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS ?? '3000', 'CHAT_PHASE7_ROUTE_TIMEOUT_MS'),
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--verify-routing-after-restore') {
      options.verifyRoutingAfterRestore = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--routing-check-base-url') {
      options.routingCheckBaseUrl = value;
    } else if (arg === '--routing-check-admin-token') {
      options.routingCheckAdminToken = value;
    } else if (arg === '--routing-check-timeout-ms') {
      options.routingCheckTimeoutMs = positiveInteger(value, arg);
    }
    // Keep existing branches for Phase 6 arguments.
  }

  return options;
}

export function buildRoutingCheckArgs(options) {
  return [
    '--base-url',
    options.routingCheckBaseUrl,
    '--admin-token',
    options.routingCheckAdminToken,
    '--timeout-ms',
    String(options.routingCheckTimeoutMs),
    '--json',
  ];
}
```

When applying this step, preserve all existing Phase 6 argument branches exactly and insert the new branches into the existing chain.

- [x] **Step 2: Run test to verify it passes**

Run:

```bash
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
```

Expected: PASS.

---

### Task 3: RED/GREEN Smoke Script Integration

**Files:**
- Modify: `scripts/phase6-fanout-takeover-smoke.mjs`
- Test: `node --check scripts/phase6-fanout-takeover-smoke.mjs`

- [x] **Step 1: Implement opt-in execution after restore**

```javascript
import {
  buildLoadChatArgs,
  buildRoutingCheckArgs,
  buildRunCapturedOptions,
  findOwnerContainer,
  parseDockerInspectRows,
  parseLoadChatJson,
  parseTakeoverSmokeArgs,
  parseWorkerContainerIds,
  redisOwnerScanPattern,
} from './lib/phase6TakeoverSmokePlan.mjs';
```

In the `finally` block, replace the direct restore call with:

```javascript
      runCaptured(
        'docker',
        ['compose', 'up', '-d', '--scale', `${options.service}=${options.restoreScale}`, '--no-build'],
        runOptions,
      );
      if (options.verifyRoutingAfterRestore) {
        runRoutingCheckAfterRestore({ options, env, runOptions });
      }
```

Add helper:

```javascript
function runRoutingCheckAfterRestore({ options, env, runOptions }) {
  const routingCheckScript = fileURLToPath(new URL('./phase7-role-routing-check.mjs', import.meta.url));
  try {
    runCaptured(
      process.execPath,
      [routingCheckScript, ...buildRoutingCheckArgs(options)],
      { ...runOptions, env },
    );
  } catch (error) {
    throw new Error(`phase7 routing check after restore failed\n${error.stderr ?? error.message}`);
  }
}
```

- [x] **Step 2: Run syntax check**

Run:

```bash
node --check scripts/phase6-fanout-takeover-smoke.mjs
```

Expected: PASS.

---

### Task 4: Documentation And Slice Index

**Files:**
- Modify: `docs/phase7_nginx_role_routing_check.md`
- Modify: `docs/phase7_slices.md`

- [x] **Step 1: Update operational documentation**

Add a section:

````markdown
## Takeover Smoke와 함께 실행

worker owner kill smoke의 restore 직후 routing check를 함께 검증하려면 opt-in 옵션을 사용한다.

```bash
node scripts/phase6-fanout-takeover-smoke.mjs \
  --room phase7-routing \
  --viewers 3 \
  --messages-per-sec 20 \
  --duration 20 \
  --kill-after 5 \
  --verify-routing-after-restore \
  --routing-check-base-url http://localhost \
  --routing-check-admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

기본값에서는 routing check가 실행되지 않는다. 이 옵션은 Phase 7 release gate용이다.
````

- [x] **Step 2: Update slice index**

Update slice 2 to:

```markdown
| 2 | Takeover smoke routing check opt-in integration | [설계](./superpowers/specs/2026-06-21-phase7-takeover-routing-check-integration-design.md), [계획](./superpowers/plans/2026-06-21-phase7-takeover-routing-check-integration.md), [운영 문서](./phase7_nginx_role_routing_check.md) | 구현 완료 |
```

---

### Task 5: Verification And Commit

**Files:**
- All files from Tasks 1-4

- [x] **Step 1: Run focused verification**

Run:

```bash
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
node --check scripts/phase6-fanout-takeover-smoke.mjs
node --check scripts/phase7-role-routing-check.mjs
git diff --check
```

Expected: all commands exit `0`.

- [x] **Step 2: Run actual routing check**

Run:

```bash
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

Expected when Compose is running with correct routing: JSON summary with `"ok": true`.

- [x] **Step 3: Commit**

```bash
git add \
  docs/phase7_nginx_role_routing_check.md \
  docs/phase7_slices.md \
  docs/superpowers/plans/2026-06-21-phase7-takeover-routing-check-integration.md \
  scripts/phase6-fanout-takeover-smoke.mjs \
  scripts/lib/phase6TakeoverSmokePlan.mjs \
  scripts/lib/phase6TakeoverSmokePlan.test.mjs
git commit -m "feat: run phase7 routing check after takeover restore"
```

---

## Self-Review

- Spec coverage: opt-in only, restore-after placement, failure propagation, docs, and slice index are covered.
- Placeholder scan: no deferred implementation markers remain.
- Type consistency: `verifyRoutingAfterRestore`, `routingCheckBaseUrl`, `routingCheckAdminToken`, `routingCheckTimeoutMs`, and `buildRoutingCheckArgs` are used consistently.
