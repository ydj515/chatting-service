# Phase 7 Nginx Role Routing Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Phase 7 첫 슬라이스로 Docker Compose Nginx stale upstream 오라우팅을 감지하는 role routing synthetic check를 구현한다.

**Architecture:** 네트워크 없는 순수 판정 로직은 `scripts/lib/phase7RoleRoutingCheckPlan.mjs`에 둔다. 실제 CLI는 `scripts/phase7-role-routing-check.mjs`에서 HTTP 요청과 WebSocket Upgrade 후보 요청을 실행하고 JSON summary와 exit code를 반환한다.

**Tech Stack:** Node.js ESM, `node:test`, `node:assert/strict`, built-in `fetch`, built-in `http`/`https`.

---

## File Structure

- Create: `scripts/lib/phase7RoleRoutingCheckPlan.test.mjs`
  - CLI 인자 파싱, check plan 생성, HTTP/WebSocket 응답 판정, summary 판정을 테스트한다.
- Create: `scripts/lib/phase7RoleRoutingCheckPlan.mjs`
  - `parseRoleRoutingCheckArgs`, `buildRoleRoutingChecks`, `evaluateHttpCheckResponse`, `evaluateWebSocketHandshakeResponse`, `summarizeRoleRoutingChecks`, `exitCodeForSummary`를 제공한다.
- Create: `scripts/phase7-role-routing-check.mjs`
  - 실제 route check를 실행하고 JSON summary를 출력한다.
- Create: `docs/phase7_nginx_role_routing_check.md`
  - 실행 절차, Nginx recreate/restart 대응, 실패 해석 방법을 문서화한다.
- Modify: `docs/phase7_slices.md`
  - 첫 슬라이스 상태를 `설계 문서 작성 완료`에서 `구현 완료`로 갱신하고 plan/script/doc 링크를 추가한다.

---

### Task 1: Plan Utility RED Tests

**Files:**
- Create: `scripts/lib/phase7RoleRoutingCheckPlan.test.mjs`
- Test: `node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs`

- [x] **Step 1: Write the failing test**

```javascript
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildRoleRoutingChecks,
  evaluateHttpCheckResponse,
  evaluateWebSocketHandshakeResponse,
  exitCodeForSummary,
  parseRoleRoutingCheckArgs,
  summarizeRoleRoutingChecks,
} from './phase7RoleRoutingCheckPlan.mjs';

test('parseRoleRoutingCheckArgs maps CLI values over environment defaults', () => {
  const options = parseRoleRoutingCheckArgs(
    ['--base-url', 'http://localhost:8088/', '--admin-token', 'secret', '--timeout-ms', '1200', '--json'],
    {
      CHAT_PHASE7_BASE_URL: 'http://ignored',
      CHAT_ADMIN_TOKEN: 'ignored',
      CHAT_PHASE7_ROUTE_TIMEOUT_MS: '9999',
    },
  );

  assert.deepEqual(options, {
    baseUrl: 'http://localhost:8088',
    adminToken: 'secret',
    timeoutMs: 1200,
    json: true,
  });
});

test('buildRoleRoutingChecks creates role-specific checks without actuator fallback', () => {
  const checks = buildRoleRoutingChecks({ baseUrl: 'http://localhost', adminToken: 'test', timeoutMs: 3000 });

  assert.deepEqual(
    checks.map((check) => [check.name, check.method, check.path, check.expectedStatus]),
    [
      ['nginx-health', 'GET', '/health', 200],
      ['api-ws-ticket-auth-required', 'POST', '/api/ws-tickets', 400],
      ['admin-health', 'GET', '/api/admin/health', 200],
      ['websocket-invalid-ticket-handshake', 'GET', '/api/ws/chat?ticket=phase7-invalid-routing-ticket', 401],
    ],
  );
  assert.equal(checks.some((check) => check.path.includes('actuator')), false);
});

test('evaluateHttpCheckResponse accepts expected status and JSON body pattern', () => {
  const check = buildRoleRoutingChecks({ baseUrl: 'http://localhost', adminToken: 'test', timeoutMs: 3000 })
    .find((candidate) => candidate.name === 'api-ws-ticket-auth-required');

  const result = evaluateHttpCheckResponse(check, {
    status: 400,
    body: '{"status":400,"path":"/api/ws-tickets"}',
    headers: { 'content-type': 'application/json' },
  });

  assert.equal(result.ok, true);
  assert.equal(result.status, 400);
});

test('evaluateHttpCheckResponse rejects stale upstream status', () => {
  const check = buildRoleRoutingChecks({ baseUrl: 'http://localhost', adminToken: 'test', timeoutMs: 3000 })
    .find((candidate) => candidate.name === 'api-ws-ticket-auth-required');

  const result = evaluateHttpCheckResponse(check, {
    status: 404,
    body: '{"path":"/api/ws-tickets"}',
    headers: { 'content-type': 'application/json' },
  });

  assert.equal(result.ok, false);
  assert.equal(result.reason, 'unexpected_status');
});

test('evaluateWebSocketHandshakeResponse accepts invalid ticket rejection from websocket role only', () => {
  const check = buildRoleRoutingChecks({ baseUrl: 'http://localhost', adminToken: 'test', timeoutMs: 3000 })
    .find((candidate) => candidate.name === 'websocket-invalid-ticket-handshake');

  assert.equal(evaluateWebSocketHandshakeResponse(check, { status: 401, body: '' }).ok, true);
  assert.equal(evaluateWebSocketHandshakeResponse(check, { status: 404, body: '' }).ok, false);
});

test('summarizeRoleRoutingChecks reports failed names and exit code', () => {
  const summary = summarizeRoleRoutingChecks({
    baseUrl: 'http://localhost',
    checks: [
      { name: 'nginx-health', ok: true, status: 200 },
      { name: 'api-ws-ticket-auth-required', ok: false, status: 404, reason: 'unexpected_status' },
    ],
  });

  assert.equal(summary.ok, false);
  assert.deepEqual(summary.failed, ['api-ws-ticket-auth-required']);
  assert.equal(exitCodeForSummary(summary), 1);
});
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
```

Expected: FAIL with module not found for `phase7RoleRoutingCheckPlan.mjs`.

---

### Task 2: Plan Utility GREEN Implementation

**Files:**
- Create: `scripts/lib/phase7RoleRoutingCheckPlan.mjs`
- Test: `scripts/lib/phase7RoleRoutingCheckPlan.test.mjs`

- [x] **Step 1: Write minimal implementation**

```javascript
export function parseRoleRoutingCheckArgs(argv, env = process.env) {
  const options = {
    baseUrl: env.CHAT_PHASE7_BASE_URL ?? 'http://localhost',
    adminToken: env.CHAT_ADMIN_TOKEN ?? 'test',
    timeoutMs: positiveInteger(env.CHAT_PHASE7_ROUTE_TIMEOUT_MS ?? '3000', 'CHAT_PHASE7_ROUTE_TIMEOUT_MS'),
    json: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--json') {
      options.json = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--base-url') {
      options.baseUrl = value;
    } else if (arg === '--admin-token') {
      options.adminToken = value;
    } else if (arg === '--timeout-ms') {
      options.timeoutMs = positiveInteger(value, arg);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return {
    ...options,
    baseUrl: normalizeBaseUrl(options.baseUrl),
  };
}

export function buildRoleRoutingChecks({ baseUrl, adminToken, timeoutMs }) {
  const normalizedBaseUrl = normalizeBaseUrl(baseUrl);
  return [
    httpCheck({ name: 'nginx-health', method: 'GET', path: '/health', expectedStatus: 200, baseUrl: normalizedBaseUrl, timeoutMs }),
    httpCheck({
      name: 'api-ws-ticket-auth-required',
      method: 'POST',
      path: '/api/ws-tickets',
      expectedStatus: 400,
      baseUrl: normalizedBaseUrl,
      timeoutMs,
      headers: { 'content-type': 'application/json' },
      body: '',
      bodyIncludes: ['"status":400', '"/api/ws-tickets"'],
    }),
    httpCheck({
      name: 'admin-health',
      method: 'GET',
      path: '/api/admin/health',
      expectedStatus: 200,
      baseUrl: normalizedBaseUrl,
      timeoutMs,
      headers: { 'X-Admin-Token': adminToken },
      bodyIncludes: ['"status"', '"ok"'],
    }),
    {
      name: 'websocket-invalid-ticket-handshake',
      kind: 'websocket-handshake',
      method: 'GET',
      path: '/api/ws/chat?ticket=phase7-invalid-routing-ticket',
      expectedStatus: 401,
      url: new URL('/api/ws/chat?ticket=phase7-invalid-routing-ticket', `${normalizedBaseUrl}/`).toString(),
      timeoutMs,
    },
  ];
}

export function evaluateHttpCheckResponse(check, response) {
  if (response.status !== check.expectedStatus) {
    return checkResult(check, false, response.status, 'unexpected_status');
  }
  for (const expected of check.bodyIncludes ?? []) {
    if (!String(response.body ?? '').includes(expected)) {
      return checkResult(check, false, response.status, 'body_mismatch');
    }
  }
  return checkResult(check, true, response.status);
}

export function evaluateWebSocketHandshakeResponse(check, response) {
  if (response.status !== check.expectedStatus) {
    return checkResult(check, false, response.status, 'unexpected_status');
  }
  return checkResult(check, true, response.status);
}

export function summarizeRoleRoutingChecks({ baseUrl, checks }) {
  const failed = checks.filter((check) => !check.ok).map((check) => check.name);
  return {
    ok: failed.length === 0,
    baseUrl: normalizeBaseUrl(baseUrl),
    checks,
    failed,
  };
}

export function exitCodeForSummary(summary) {
  return summary.ok ? 0 : 1;
}

function httpCheck({ name, method, path, expectedStatus, baseUrl, timeoutMs, headers = {}, body, bodyIncludes = [] }) {
  return {
    name,
    kind: 'http',
    method,
    path,
    expectedStatus,
    url: new URL(path, `${baseUrl}/`).toString(),
    timeoutMs,
    headers,
    body,
    bodyIncludes,
  };
}

function checkResult(check, ok, status, reason) {
  return {
    name: check.name,
    method: check.method,
    url: check.url,
    ok,
    status,
    ...(reason ? { reason } : {}),
  };
}

function normalizeBaseUrl(value) {
  return String(value).replace(/\/+$/, '');
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}
```

- [x] **Step 2: Run test to verify it passes**

Run:

```bash
node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
```

Expected: PASS.

---

### Task 3: CLI RED Tests Through Syntax And Contract

**Files:**
- Create: `scripts/phase7-role-routing-check.mjs`
- Test: `node --check scripts/phase7-role-routing-check.mjs`

- [x] **Step 1: Create CLI with imports and no implementation body**

```javascript
#!/usr/bin/env node
import {
  buildRoleRoutingChecks,
  exitCodeForSummary,
  parseRoleRoutingCheckArgs,
  summarizeRoleRoutingChecks,
} from './lib/phase7RoleRoutingCheckPlan.mjs';

async function main() {
  const options = parseRoleRoutingCheckArgs(process.argv.slice(2));
  const checks = buildRoleRoutingChecks(options);
  const results = [];
  const summary = summarizeRoleRoutingChecks({ baseUrl: options.baseUrl, checks: results });
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = exitCodeForSummary(summary);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
```

- [x] **Step 2: Run syntax check**

Run:

```bash
node --check scripts/phase7-role-routing-check.mjs
```

Expected: PASS syntax check, but CLI is not behaviorally complete because it returns an empty summary.

---

### Task 4: CLI GREEN Implementation

**Files:**
- Modify: `scripts/phase7-role-routing-check.mjs`
- Test: `node --check scripts/phase7-role-routing-check.mjs`

- [x] **Step 1: Implement HTTP and WebSocket check execution**

```javascript
#!/usr/bin/env node
import http from 'node:http';
import https from 'node:https';
import {
  buildRoleRoutingChecks,
  evaluateHttpCheckResponse,
  evaluateWebSocketHandshakeResponse,
  exitCodeForSummary,
  parseRoleRoutingCheckArgs,
  summarizeRoleRoutingChecks,
} from './lib/phase7RoleRoutingCheckPlan.mjs';

async function main() {
  const options = parseRoleRoutingCheckArgs(process.argv.slice(2));
  const checks = buildRoleRoutingChecks(options);
  const results = [];

  for (const check of checks) {
    try {
      if (check.kind === 'websocket-handshake') {
        results.push(evaluateWebSocketHandshakeResponse(check, await requestWebSocketHandshake(check)));
      } else {
        results.push(evaluateHttpCheckResponse(check, await requestHttp(check)));
      }
    } catch (error) {
      results.push({
        name: check.name,
        method: check.method,
        url: check.url,
        ok: false,
        status: null,
        reason: 'request_failed',
        error: error.message,
      });
    }
  }

  const summary = summarizeRoleRoutingChecks({ baseUrl: options.baseUrl, checks: results });
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = exitCodeForSummary(summary);
}

function requestHttp(check) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), check.timeoutMs);
  return fetch(check.url, {
    method: check.method,
    headers: check.headers,
    body: check.body,
    signal: controller.signal,
  }).then(async (response) => {
    clearTimeout(timeout);
    return {
      status: response.status,
      headers: Object.fromEntries(response.headers.entries()),
      body: await response.text(),
    };
  }).catch((error) => {
    clearTimeout(timeout);
    throw error;
  });
}

function requestWebSocketHandshake(check) {
  const url = new URL(check.url);
  const transport = url.protocol === 'https:' ? https : http;
  const key = Buffer.from(`phase7-${Date.now()}`).toString('base64').slice(0, 24);

  return new Promise((resolve, reject) => {
    const request = transport.request({
      method: 'GET',
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port,
      path: `${url.pathname}${url.search}`,
      headers: {
        Host: url.host,
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Key': key,
        'Sec-WebSocket-Version': '13',
      },
      timeout: check.timeoutMs,
    }, async (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        resolve({
          status: response.statusCode,
          headers: response.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        });
      });
    });

    request.on('upgrade', (response, socket) => {
      socket.destroy();
      resolve({
        status: response.statusCode,
        headers: response.headers,
        body: '',
      });
    });
    request.on('timeout', () => {
      request.destroy(new Error(`Timed out after ${check.timeoutMs}ms`));
    });
    request.on('error', reject);
    request.end();
  });
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
```

- [x] **Step 2: Run syntax check**

Run:

```bash
node --check scripts/phase7-role-routing-check.mjs
```

Expected: PASS.

---

### Task 5: Documentation And Slice Index

**Files:**
- Create: `docs/phase7_nginx_role_routing_check.md`
- Modify: `docs/phase7_slices.md`

- [x] **Step 1: Write operational documentation**

```markdown
# Phase 7 Nginx Role Routing Synthetic Check

이 문서는 Docker Compose 환경에서 app rebuild/recreate/scale 이후 Nginx stale upstream으로 인한 role 오라우팅을 검증하는 방법을 정리한다.

## 1. 조건

- Phase 7 본체 첫 슬라이스다.
- Phase7-pre 인프라 작업은 포함하지 않는다.
- Nginx는 Compose 서비스 이름을 upstream으로 사용한다.
- app container IP가 바뀌면 Nginx가 stale upstream을 들고 있을 수 있다.

## 2. 실행

```bash
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "$CHAT_ADMIN_TOKEN"
```

## 3. 대응 절차

app recreate/scale 이후 먼저 Nginx를 유지한 상태로 check를 실행한다. 실패하면 Nginx를 recreate하고 다시 check한다.

```bash
docker compose up -d --force-recreate --no-build nginx
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "$CHAT_ADMIN_TOKEN"
```

## 4. 복잡도

- route check 시간 복잡도: `O(R)`
- summary 공간 복잡도: `O(R)`

## 5. 주의사항

> - `api-ws-ticket-auth-required`는 정상 API role에서 인증 실패 `400`을 기대한다.
> - `websocket-invalid-ticket-handshake`는 정상 WebSocket role에서 invalid ticket `401`을 기대한다.
> - `404`는 stale upstream 또는 잘못된 role routing 후보로 본다.

## 6. 대안

| 대안 | 장점 | 단점 |
| --- | --- | --- |
| Nginx recreate 후 check | 단순하고 Compose 기간에 충분하다 | 자동 DNS 재해석은 아니다 |
| Nginx resolver 기반 동적 upstream | restart 의존도를 줄일 수 있다 | Compose 한정 복잡도가 커진다 |
```

- [x] **Step 2: Update slice index**

Update `docs/phase7_slices.md` first slice status to:

```markdown
| 1 | Nginx stale upstream synthetic check | [설계](./superpowers/specs/2026-06-21-phase7-nginx-role-routing-design.md), [계획](./superpowers/plans/2026-06-21-phase7-nginx-role-routing-check.md), [운영 문서](./phase7_nginx_role_routing_check.md) | 구현 완료 |
```

---

### Task 6: Verification And Commit

**Files:**
- All files from Tasks 1-5

- [x] **Step 1: Run focused tests**

Run:

```bash
node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
node --check scripts/phase7-role-routing-check.mjs
git diff --check
```

Expected: all commands exit `0`.

- [x] **Step 2: Run actual synthetic check when Compose is available**

Run:

```bash
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

Expected when Compose is running with correct routing: JSON summary with `"ok": true`. If Compose is not running, record the request failure summary and do not claim runtime synthetic check success.

- [x] **Step 3: Commit**

```bash
git add \
  docs/phase7_nginx_role_routing_check.md \
  docs/phase7_slices.md \
  docs/superpowers/plans/2026-06-21-phase7-nginx-role-routing-check.md \
  scripts/phase7-role-routing-check.mjs \
  scripts/lib/phase7RoleRoutingCheckPlan.mjs \
  scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
git commit -m "feat: add phase7 nginx routing check"
```

---

## Self-Review

- Spec coverage: the plan covers route-specific checks, JSON summary, exit code, operational documentation, and slice index updates.
- Placeholder scan: the plan does not use undefined placeholders or deferred implementation markers.
- Type consistency: exported function names are consistent across tests, utility module, and CLI script.
