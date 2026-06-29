# Phase 8.4 Staged Release Gate Implementation Plan

> **에이전트 작업자용:** 필수 하위 스킬은 `superpowers:subagent-driven-development` 또는 `superpowers:executing-plans`다. 이 계획은 task 단위로 추적할 수 있도록 checkbox(`- [ ]`) 문법을 사용한다.

**Goal:** Phase 8.4 release gate를 `1k -> 3k -> 5k -> 7k -> 10k` 단계형 fail-fast gate로 바꾸고, 실패 stage와 load runner 진단 정보를 JSON artifact로 남긴다.

**Architecture:** `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`가 CLI와 stage 모델을 소유하고, 새 `scripts/lib/phase8HotRoomReleaseGateRunner.mjs`가 dependency-injected staged orchestration을 소유한다. `scripts/load-chat.mjs`는 `scripts/lib/loadChatPlan.mjs`의 count-only collector와 step diagnostic helper를 사용해 대규모 stage에서 메모리 사용량과 실패 원인 가시성을 개선한다.

**Tech Stack:** Node.js built-in test runner, JavaScript ESM, Docker Compose, Nginx official image template entrypoint, Markdown docs.

---

## 파일 구조

- Modify: `scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`
  - stage parsing, single-stage compatibility, load-chat argument contract, result shape pure test를 추가한다.
- Modify: `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`
  - `--stages`, `--single-stage`, stage naming, threshold/result helper를 추가한다.
- Create: `scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs`
  - staged orchestration이 성공 stage를 순서대로 실행하고 실패 stage에서 fail-fast 하는지 검증한다.
- Create: `scripts/lib/phase8HotRoomReleaseGateRunner.mjs`
  - load runner와 Prometheus snapshot dependency를 주입받아 stage별 결과 JSON을 만든다.
- Modify: `scripts/phase8-hot-room-release-gate.mjs`
  - thin CLI wrapper로 축소하고 성공/실패 모두 stdout JSON artifact를 출력한다.
- Modify: `scripts/lib/loadChatPlan.test.mjs`
  - count-only collector, `--summary-mode`, `--label`, diagnostic formatting test를 추가한다.
- Modify: `scripts/lib/loadChatPlan.mjs`
  - count-only collector, count 기반 minimum assertion, diagnostic formatting, URL redaction helper를 추가한다.
- Modify: `scripts/load-chat.mjs`
  - stage label, count-only mode, step-level diagnostic wrapper를 실제 HTTP/WebSocket 준비 단계에 연결한다.
- Modify: `scripts/lib/dockerComposeWorkerScale.test.mjs`
  - nginx worker connection과 nofile budget이 compose/main config에 명시되는지 검증한다.
- Create: `infra/nginx/nginx.main.conf.template`
  - `worker_processes`와 `events.worker_connections`를 env 기반으로 설정하는 main nginx config template이다.
- Modify: `docker-compose.yml`
  - nginx service에 main config template mount, command envsubst, nofile ulimit, worker env를 추가한다.
- Modify: `README.md`
  - staged release gate 실행법과 rate-limit/connection budget 안내를 갱신한다.
- Modify: `docs/infrastructure.md`
  - Phase 8.4 gate 설명을 10k 단일 gate에서 staged gate로 바꾼다.

---

### Task 1: Stage CLI Contract

**Files:**
- Modify: `scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`
- Modify: `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`

- [ ] **Step 1: Write the failing tests for staged defaults and single-stage compatibility**

`scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`의 import 목록을 다음처럼 바꾼다.

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildLoadChatArgs,
  buildStageOptions,
  parsePhase8HotRoomGateArgs,
  prometheusQueryNumber,
  prometheusQueries,
  stageName,
} from './phase8HotRoomReleaseGatePlan.mjs';
```

기존 `parsePhase8HotRoomGateArgs defaults to 10k messages per second for 60 seconds` test를 다음 test로 교체한다.

```js
test('parsePhase8HotRoomGateArgs defaults to staged 1k 3k 5k 7k 10k gate', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.equal(options.mode, 'staged');
  assert.equal(options.room, 'hot');
  assert.deepEqual(options.stages, [
    { name: '1k', viewers: 1000, messagesPerSec: 1000, durationSeconds: 60 },
    { name: '3k', viewers: 3000, messagesPerSec: 3000, durationSeconds: 60 },
    { name: '5k', viewers: 5000, messagesPerSec: 5000, durationSeconds: 60 },
    { name: '7k', viewers: 7000, messagesPerSec: 7000, durationSeconds: 60 },
    { name: '10k', viewers: 10000, messagesPerSec: 10000, durationSeconds: 60 },
  ]);
  assert.equal(options.minReceivedRatio, 0.9);
  assert.equal(options.minStreamShardCount, 16);
  assert.equal(options.maxFanoutP95Ms, 500);
  assert.equal(options.maxStreamGroupLagEntries, 1000);
});

test('parsePhase8HotRoomGateArgs accepts custom staged viewer list', () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,3000,5000']);

  assert.equal(options.mode, 'staged');
  assert.deepEqual(options.stages.map((stage) => stage.name), ['1k', '3k', '5k']);
  assert.deepEqual(options.stages.map((stage) => stage.viewers), [1000, 3000, 5000]);
  assert.deepEqual(options.stages.map((stage) => stage.messagesPerSec), [1000, 3000, 5000]);
});

test('parsePhase8HotRoomGateArgs keeps explicit single-stage compatibility mode', () => {
  const options = parsePhase8HotRoomGateArgs([
    '--single-stage',
    '--viewers', '7000',
    '--messages-per-sec', '6500',
    '--duration', '30',
  ]);

  assert.equal(options.mode, 'single-stage');
  assert.deepEqual(options.stages, [
    { name: '7k', viewers: 7000, messagesPerSec: 6500, durationSeconds: 30 },
  ]);
});

test('parsePhase8HotRoomGateArgs rejects ambiguous staged and single-stage options', () => {
  assert.throws(
    () => parsePhase8HotRoomGateArgs(['--stages', '1000,5000', '--messages-per-sec', '2000']),
    /--messages-per-sec requires --single-stage/,
  );
  assert.throws(
    () => parsePhase8HotRoomGateArgs(['--stages', '1000,5000', '--viewers', '1000']),
    /--viewers requires --single-stage/,
  );
});

test('stageName formats thousand stages with k suffix and raw integers otherwise', () => {
  assert.equal(stageName(1000), '1k');
  assert.equal(stageName(5000), '5k');
  assert.equal(stageName(750), '750');
});
```

기존 `buildLoadChatArgs includes release gate load arguments` test를 다음처럼 교체한다.

```js
test('buildLoadChatArgs includes staged release gate load arguments', () => {
  const options = parsePhase8HotRoomGateArgs(['--room', 'arena']);
  const args = buildLoadChatArgs(options, options.stages[0]);

  assert.deepEqual(args, [
    'scripts/load-chat.mjs',
    '--room', 'arena',
    '--viewers', '1000',
    '--messages-per-sec', '1000',
    '--duration', '60',
    '--min-received-ratio', '0.9',
    '--summary-mode', 'counts',
    '--label', '1k',
  ]);
});
```

Update the existing `assertLoadSummary` tests in the same file so their option parsing uses explicit single-stage mode during this task.

```js
const options = parsePhase8HotRoomGateArgs(['--single-stage', '--viewers', '2']);
```

For the insufficient-delivery test that uses one viewer, use:

```js
const options = parsePhase8HotRoomGateArgs(['--single-stage', '--viewers', '1']);
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: FAIL with missing exports such as `buildStageOptions` or missing `mode`/`stages` fields.

- [ ] **Step 3: Implement the stage parser and load arg contract**

In `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`, add constants and helpers above `parsePhase8HotRoomGateArgs`.

```js
const DEFAULT_STAGE_VIEWERS = [1000, 3000, 5000, 7000, 10000];

export function stageName(viewers) {
  return viewers % 1000 === 0 ? `${viewers / 1000}k` : String(viewers);
}

export function buildStageOptions(viewers, { messagesPerSec = viewers, durationSeconds = 60 } = {}) {
  return {
    name: stageName(viewers),
    viewers,
    messagesPerSec,
    durationSeconds,
  };
}

function parseStageList(value, name) {
  const parsed = value.split(',').map((entry) => positiveInteger(entry.trim(), name));
  if (parsed.length === 0) {
    throw new Error(`${name} must include at least one stage`);
  }
  return parsed;
}
```

Replace `parsePhase8HotRoomGateArgs` with this implementation.

```js
export function parsePhase8HotRoomGateArgs(argv, env = process.env) {
  const options = {
    mode: 'staged',
    room: 'hot',
    stages: DEFAULT_STAGE_VIEWERS.map((viewers) => buildStageOptions(viewers)),
    durationSeconds: 60,
    minReceivedRatio: 0.9,
    minStreamShardCount: 16,
    maxFanoutP95Ms: 500,
    maxStreamGroupLagEntries: 1000,
    prometheusUrl: env.CHAT_PROMETHEUS_URL ?? 'http://localhost:9090',
    loadScript: 'scripts/load-chat.mjs',
  };
  let singleStage = false;
  let explicitViewers = null;
  let explicitMessagesPerSec = null;
  let explicitStages = null;

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--single-stage') {
      singleStage = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--room') {
      options.room = value;
    } else if (arg === '--stages') {
      explicitStages = parseStageList(value, arg);
    } else if (arg === '--viewers') {
      explicitViewers = positiveInteger(value, arg);
    } else if (arg === '--messages-per-sec') {
      explicitMessagesPerSec = positiveInteger(value, arg);
    } else if (arg === '--duration') {
      options.durationSeconds = positiveInteger(value, arg);
    } else if (arg === '--min-received-ratio') {
      options.minReceivedRatio = ratio(value, arg);
    } else if (arg === '--min-stream-shards') {
      options.minStreamShardCount = positiveInteger(value, arg);
    } else if (arg === '--max-fanout-p95-ms') {
      options.maxFanoutP95Ms = positiveInteger(value, arg);
    } else if (arg === '--max-stream-lag') {
      options.maxStreamGroupLagEntries = positiveInteger(value, arg);
    } else if (arg === '--prometheus-url') {
      options.prometheusUrl = value;
    } else if (arg === '--load-script') {
      options.loadScript = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (explicitStages && singleStage) {
    throw new Error('--stages cannot be used with --single-stage');
  }
  if (!singleStage && explicitViewers !== null) {
    throw new Error('--viewers requires --single-stage');
  }
  if (!singleStage && explicitMessagesPerSec !== null) {
    throw new Error('--messages-per-sec requires --single-stage');
  }

  if (singleStage) {
    const viewers = explicitViewers ?? 10000;
    options.mode = 'single-stage';
    options.stages = [
      buildStageOptions(viewers, {
        messagesPerSec: explicitMessagesPerSec ?? viewers,
        durationSeconds: options.durationSeconds,
      }),
    ];
  } else if (explicitStages) {
    options.stages = explicitStages.map((viewers) => buildStageOptions(viewers, {
      durationSeconds: options.durationSeconds,
    }));
  } else {
    options.stages = DEFAULT_STAGE_VIEWERS.map((viewers) => buildStageOptions(viewers, {
      durationSeconds: options.durationSeconds,
    }));
  }

  const compatibilityStage = options.stages[0];
  options.viewers = compatibilityStage.viewers;
  options.messagesPerSec = compatibilityStage.messagesPerSec;

  return options;
}
```

Update `buildLoadChatArgs` to accept a stage.

```js
export function buildLoadChatArgs(options, stage) {
  return [
    options.loadScript,
    '--room', options.room,
    '--viewers', String(stage.viewers),
    '--messages-per-sec', String(stage.messagesPerSec),
    '--duration', String(stage.durationSeconds),
    '--min-received-ratio', String(options.minReceivedRatio),
    '--summary-mode', 'counts',
    '--label', stage.name,
  ];
}
```

- [ ] **Step 4: Run the focused tests and verify they pass**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/phase8HotRoomReleaseGatePlan.mjs scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
git commit -m "feat: parse staged phase8 release gate options"
```

---

### Task 2: Gate Result Helpers

**Files:**
- Modify: `scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`
- Modify: `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`

- [ ] **Step 1: Write failing tests for stage result JSON**

Add the new exports to the import list.

```js
  buildFailedGateResult,
  buildGateResult,
  buildStageFailure,
  buildStageSuccess,
```

Add these tests near the load summary tests.

```js
test('buildGateResult reports last passed and failed stage names', () => {
  const options = parsePhase8HotRoomGateArgs([]);
  const result = buildGateResult(options, [
    buildStageSuccess(options.stages[0], { ok: true }, { fanoutP95Seconds: 0.1 }),
    buildStageSuccess(options.stages[1], { ok: true }, { fanoutP95Seconds: 0.2 }),
    buildStageFailure(options.stages[2], new Error('load runner failed')),
  ]);

  assert.equal(result.ok, false);
  assert.equal(result.mode, 'staged');
  assert.equal(result.lastPassedStage, '3k');
  assert.equal(result.failedStage, '5k');
  assert.equal(result.stages.length, 3);
  assert.equal(result.stages[2].error.message, 'load runner failed');
});

test('buildGateResult reports all stages passed', () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,5000']);
  const result = buildGateResult(options, [
    buildStageSuccess(options.stages[0], { ok: true }, { fanoutP95Seconds: 0.1 }),
    buildStageSuccess(options.stages[1], { ok: true }, { fanoutP95Seconds: 0.2 }),
  ]);

  assert.equal(result.ok, true);
  assert.equal(result.lastPassedStage, '5k');
  assert.equal(result.failedStage, null);
  assert.equal(result.thresholds.maxFanoutP95Ms, 500);
});

test('buildFailedGateResult creates JSON-safe error objects with stderr', () => {
  const options = parsePhase8HotRoomGateArgs([]);
  const error = new Error('load runner failed');
  error.stderr = 'viewer 5104 issue-ticket POST http://localhost/api/ws-tickets failed: fetch failed';

  const result = buildFailedGateResult(options, options.stages[0], error, []);

  assert.equal(result.ok, false);
  assert.equal(result.failedStage, '1k');
  assert.match(result.stages[0].error.stderr, /viewer 5104/);
});
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: FAIL with missing result helper exports.

- [ ] **Step 3: Implement result helper functions**

Append these functions to `scripts/lib/phase8HotRoomReleaseGatePlan.mjs` before `positiveInteger`.

```js
export function buildStageSuccess(stage, loadSummary, prometheusSnapshot) {
  return {
    name: stage.name,
    ok: true,
    options: {
      viewers: stage.viewers,
      messagesPerSec: stage.messagesPerSec,
      durationSeconds: stage.durationSeconds,
    },
    loadSummary,
    prometheusSnapshot,
  };
}

export function buildStageFailure(stage, error) {
  return {
    name: stage.name,
    ok: false,
    options: {
      viewers: stage.viewers,
      messagesPerSec: stage.messagesPerSec,
      durationSeconds: stage.durationSeconds,
    },
    error: {
      stage: stage.name,
      message: error.message,
      ...(error.stderr ? { stderr: error.stderr } : {}),
    },
  };
}

export function gateThresholds(options) {
  return {
    minStreamShardCount: options.minStreamShardCount,
    maxFanoutP95Ms: options.maxFanoutP95Ms,
    maxStreamGroupLagEntries: options.maxStreamGroupLagEntries,
  };
}

export function buildGateResult(options, stageResults) {
  const failed = stageResults.find((stage) => stage.ok === false) ?? null;
  const passed = stageResults.filter((stage) => stage.ok === true);
  return {
    ok: failed === null,
    mode: options.mode,
    lastPassedStage: passed.length > 0 ? passed[passed.length - 1].name : null,
    failedStage: failed?.name ?? null,
    stages: stageResults,
    thresholds: gateThresholds(options),
  };
}

export function buildFailedGateResult(options, stage, error, previousStageResults) {
  return buildGateResult(options, [
    ...previousStageResults,
    buildStageFailure(stage, error),
  ]);
}
```

- [ ] **Step 4: Run the focused tests and verify they pass**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/phase8HotRoomReleaseGatePlan.mjs scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
git commit -m "feat: add staged release gate result helpers"
```

---

### Task 3: Dependency-Injected Staged Runner

**Files:**
- Create: `scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs`
- Create: `scripts/lib/phase8HotRoomReleaseGateRunner.mjs`
- Modify: `scripts/phase8-hot-room-release-gate.mjs`

- [ ] **Step 1: Write failing orchestration tests**

Create `scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs`.

```js
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parsePhase8HotRoomGateArgs } from './phase8HotRoomReleaseGatePlan.mjs';
import { runReleaseGate } from './phase8HotRoomReleaseGateRunner.mjs';

test('runReleaseGate executes staged gates in order', async () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,5000']);
  const calls = [];

  const result = await runReleaseGate(options, {
    runLoadChat: async (stage) => {
      calls.push(stage.name);
      return {
        ok: true,
        sent: stage.messagesPerSec * stage.durationSeconds,
        viewers: stage.viewers,
        receivedPerViewer: Array.from({ length: stage.viewers }, () => stage.messagesPerSec * stage.durationSeconds),
      };
    },
    readPrometheusSnapshot: async () => ({
      fanoutP95Seconds: 0.1,
      observedStreamShardCount: 16,
      maxStreamGroupLagEntries: 0,
    }),
  });

  assert.deepEqual(calls, ['1k', '5k']);
  assert.equal(result.ok, true);
  assert.equal(result.lastPassedStage, '5k');
});

test('runReleaseGate stops after the first failing stage', async () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,3000,5000,7000,10000']);
  const calls = [];

  const result = await runReleaseGate(options, {
    runLoadChat: async (stage) => {
      calls.push(stage.name);
      if (stage.name === '5k') {
        throw new Error('load runner failed at 5k');
      }
      return {
        ok: true,
        sent: stage.messagesPerSec * stage.durationSeconds,
        viewers: stage.viewers,
        receivedPerViewer: Array.from({ length: stage.viewers }, () => stage.messagesPerSec * stage.durationSeconds),
      };
    },
    readPrometheusSnapshot: async () => ({
      fanoutP95Seconds: 0.1,
      observedStreamShardCount: 16,
      maxStreamGroupLagEntries: 0,
    }),
  });

  assert.deepEqual(calls, ['1k', '3k', '5k']);
  assert.equal(result.ok, false);
  assert.equal(result.lastPassedStage, '3k');
  assert.equal(result.failedStage, '5k');
});
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs
```

Expected: FAIL because `scripts/lib/phase8HotRoomReleaseGateRunner.mjs` does not exist.

- [ ] **Step 3: Implement the staged runner module**

Create `scripts/lib/phase8HotRoomReleaseGateRunner.mjs`.

```js
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildFailedGateResult,
  buildGateResult,
  buildStageSuccess,
} from './phase8HotRoomReleaseGatePlan.mjs';

export async function runReleaseGate(options, deps) {
  const stageResults = [];
  for (const stage of options.stages) {
    try {
      const loadSummary = await deps.runLoadChat(stage, options);
      assertLoadSummary(loadSummary, stage, options);
      const prometheusSnapshot = await deps.readPrometheusSnapshot(options.prometheusUrl, stage, options);
      assertPrometheusSnapshot(prometheusSnapshot, options);
      stageResults.push(buildStageSuccess(stage, loadSummary, prometheusSnapshot));
    } catch (error) {
      return buildFailedGateResult(options, stage, error, stageResults);
    }
  }
  return buildGateResult(options, stageResults);
}
```

Update `assertLoadSummary` in `scripts/lib/phase8HotRoomReleaseGatePlan.mjs` to accept `(summary, stage, options)`.

```js
export function assertLoadSummary(summary, stage, options) {
  if (summary?.ok !== true) {
    throw new Error('load summary did not report ok=true');
  }
  const expectedSent = stage.messagesPerSec * stage.durationSeconds;
  if (summary.sent < expectedSent) {
    throw new Error(`load summary sent ${summary.sent}; expected at least ${expectedSent}`);
  }
  if (summary.viewers !== stage.viewers) {
    throw new Error(`load summary viewers ${summary.viewers}; expected ${stage.viewers}`);
  }
  if (!Array.isArray(summary.receivedPerViewer)) {
    throw new Error('load summary receivedPerViewer must be an array');
  }
  if (summary.receivedPerViewer.length !== stage.viewers) {
    throw new Error(
      `load summary receivedPerViewer length ${summary.receivedPerViewer.length}; expected ${stage.viewers}`,
    );
  }
  const minimumReceived = Math.ceil(summary.sent * options.minReceivedRatio);
  for (const [index, received] of summary.receivedPerViewer.entries()) {
    if (received < minimumReceived) {
      throw new Error(`viewer ${index} received ${received}; minimum received is ${minimumReceived}`);
    }
  }
}
```

Adjust existing `assertLoadSummary` tests to pass `options.stages[0]` as the second argument and `options` as the third.

- [ ] **Step 4: Replace the CLI script with a thin wrapper**

Modify `scripts/phase8-hot-room-release-gate.mjs` to use `runReleaseGate`.

```js
#!/usr/bin/env node
import { spawn } from 'node:child_process';
import {
  buildLoadChatArgs,
  parsePhase8HotRoomGateArgs,
  prometheusQueryNumber,
  prometheusQueries,
} from './lib/phase8HotRoomReleaseGatePlan.mjs';
import { runReleaseGate } from './lib/phase8HotRoomReleaseGateRunner.mjs';

async function main() {
  const options = parsePhase8HotRoomGateArgs(process.argv.slice(2));
  const result = await runReleaseGate(options, {
    runLoadChat,
    readPrometheusSnapshot,
  });

  console.log(JSON.stringify(result, null, 2));
  if (!result.ok) {
    process.exitCode = 1;
  }
}

async function runLoadChat(stage, options) {
  const args = buildLoadChatArgs(options, stage);
  const output = await runProcess(process.execPath, args);
  return JSON.parse(output);
}

function runProcess(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        const error = new Error(`${command} ${args.join(' ')} failed with code ${code}`);
        error.stderr = stderr.trim();
        reject(error);
        return;
      }
      resolve(stdout.trim());
    });
  });
}

async function readPrometheusSnapshot(prometheusUrl) {
  const [fanoutP95Seconds, observedStreamShardCount, maxStreamGroupLagEntries] = await Promise.all([
    queryPrometheusNumber(prometheusUrl, prometheusQueries.fanoutP95Seconds),
    queryPrometheusNumber(prometheusUrl, prometheusQueries.observedStreamShardCount),
    queryPrometheusNumber(prometheusUrl, prometheusQueries.maxStreamGroupLagEntries),
  ]);
  return {
    fanoutP95Seconds,
    observedStreamShardCount,
    maxStreamGroupLagEntries,
  };
}

async function queryPrometheusNumber(prometheusUrl, query) {
  const url = new URL('/api/v1/query', prometheusUrl);
  url.searchParams.set('query', query);
  const response = await fetch(url);
  const body = await response.json();
  if (!response.ok || body.status !== 'success') {
    throw new Error(`Prometheus query failed: ${query}`);
  }
  return prometheusQueryNumber(body, query);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
```

- [ ] **Step 5: Run focused tests and syntax checks**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs
node --check scripts/phase8-hot-room-release-gate.mjs
```

Expected: PASS and syntax check success.

- [ ] **Step 6: Commit**

```bash
git add scripts/phase8-hot-room-release-gate.mjs scripts/lib/phase8HotRoomReleaseGatePlan.mjs scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs scripts/lib/phase8HotRoomReleaseGateRunner.mjs scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs
git commit -m "feat: run phase8 release gate in fail-fast stages"
```

---

### Task 4: Count-Only Load Summary

**Files:**
- Modify: `scripts/lib/loadChatPlan.test.mjs`
- Modify: `scripts/lib/loadChatPlan.mjs`
- Modify: `scripts/load-chat.mjs`

- [ ] **Step 1: Write failing tests for summary mode and count collector**

Add these imports in `scripts/lib/loadChatPlan.test.mjs`.

```js
  assertMinimumReceivedCounts,
  createViewerMessageCollector,
```

Add tests.

```js
test('parseLoadChatArgs supports count-only summary mode and run label', () => {
  const options = parseLoadChatArgs(['--summary-mode', 'counts', '--label', '5k']);

  assert.equal(options.summaryMode, 'counts');
  assert.equal(options.label, '5k');
});

test('parseLoadChatArgs defaults to message retention for compatibility', () => {
  const options = parseLoadChatArgs([]);

  assert.equal(options.summaryMode, 'messages');
  assert.equal(options.label, null);
});

test('assertMinimumReceivedCounts rejects insufficient received counts', () => {
  assert.throws(
    () => assertMinimumReceivedCounts([180, 200], 200, 0.95),
    /viewer 0 received only 180\/200/,
  );
});

test('createViewerMessageCollector can count without retaining every message', () => {
  const collector = createViewerMessageCollector({ retainMessages: false, sampleLimit: 2 });

  collector.addViewer(1);
  collector.record(1, [{ roomSeq: 1 }, { roomSeq: 2 }, { roomSeq: 3 }]);

  assert.deepEqual(collector.receivedCounts(), [3]);
  assert.deepEqual(collector.receivedSamples(), [null]);
  assert.deepEqual(collector.sampleSummary(), [
    {
      userId: 1,
      received: 3,
      samples: [{ roomSeq: 1 }, { roomSeq: 2 }],
    },
  ]);
});

test('createViewerMessageCollector keeps full messages when retention is enabled', () => {
  const collector = createViewerMessageCollector({ retainMessages: true });

  collector.addViewer(1);
  collector.record(1, [{ roomSeq: 1 }, { roomSeq: 2 }]);

  assert.deepEqual(collector.receivedCounts(), [2]);
  assert.deepEqual(collector.receivedSamples(), [[{ roomSeq: 1 }, { roomSeq: 2 }]]);
});
```

Update the existing `parseLoadChatArgs maps Phase 6 load-chat options` expected object so it includes the new compatibility defaults.

```js
    summaryMode: 'messages',
    label: null,
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
```

Expected: FAIL with missing count helper exports and missing `summaryMode`/`label`.

- [ ] **Step 3: Implement count-only helpers**

Update the default options in `parseLoadChatArgs`.

```js
    summaryMode: 'messages',
    label: null,
```

Add argument handling.

```js
    } else if (arg === '--summary-mode') {
      if (!['messages', 'counts'].includes(value)) {
        throw new Error('--summary-mode must be messages or counts');
      }
      options.summaryMode = value;
    } else if (arg === '--label') {
      options.label = value;
```

Add helper functions to `scripts/lib/loadChatPlan.mjs`.

```js
export function assertMinimumReceivedCounts(receivedCounts, sent, minReceivedRatio) {
  if (minReceivedRatio <= 0) {
    return;
  }
  const minimum = Math.ceil(sent * minReceivedRatio);
  receivedCounts.forEach((received, index) => {
    if (received < minimum) {
      throw new Error(
        `viewer ${index} received only ${received}/${sent}; minimum ratio ${minReceivedRatio} requires ${minimum}`,
      );
    }
  });
}

export function createViewerMessageCollector({ retainMessages, sampleLimit = 3 }) {
  const records = new Map();
  return {
    addViewer(userId) {
      records.set(userId, {
        userId,
        received: 0,
        samples: [],
        messages: retainMessages ? [] : null,
      });
    },
    record(userId, messages) {
      const record = records.get(userId);
      if (!record) {
        return;
      }
      record.received += messages.length;
      for (const message of messages) {
        if (record.samples.length < sampleLimit) {
          record.samples.push(message);
        }
      }
      if (record.messages) {
        record.messages.push(...messages);
      }
    },
    receivedCounts() {
      return [...records.values()].map((record) => record.received);
    },
    receivedSamples() {
      return [...records.values()].map((record) => record.messages);
    },
    sampleSummary() {
      return [...records.values()].map(({ userId, received, samples }) => ({
        userId,
        received,
        samples,
      }));
    },
  };
}
```

- [ ] **Step 4: Wire count-only mode into `scripts/load-chat.mjs`**

Update imports.

```js
  assertMinimumReceivedCounts,
  createViewerMessageCollector,
```

Replace `receivedByViewer` setup with collector setup.

```js
  const retainMessages = options.summaryMode === 'messages'
    || options.assertRoomSeqOrder
    || options.takeoverDeliverySummary;
  const collector = createViewerMessageCollector({ retainMessages });
  const viewers = [];
```

Inside the viewer loop, replace `receivedByViewer.set(user.id, []);` with:

```js
    collector.addViewer(user.id);
```

Replace the frame recording body with:

```js
      const messages = flattenChatMessages(frame).filter((message) => message.chatRoomId === room.id);
      if (messages.length > 0) {
        collector.record(user.id, messages);
      }
```

Replace the final summary calculation with:

```js
  const receivedCounts = collector.receivedCounts();
  assertMinimumReceivedCounts(receivedCounts, sent, options.minReceivedRatio);
  const receivedSamples = collector.receivedSamples();

  if (options.assertRoomSeqOrder) {
    for (const messages of receivedSamples) {
      assertRoomSeqOrder(messages);
    }
  }

  senderWs.close();
  viewers.forEach((viewer) => viewer.close());
  const takeoverDelivery = options.takeoverDeliverySummary
    ? summarizeTakeoverDelivery(receivedSamples, {
      sent,
      minReceivedRatio: options.minReceivedRatio,
    })
    : null;
  const summary = {
    ok: true,
    roomId: room.id,
    sent,
    viewers: options.viewers,
    receivedPerViewer: receivedCounts,
    minReceivedRatio: options.minReceivedRatio,
    assertedRoomSeqOrder: options.assertRoomSeqOrder,
    takeoverDeliverySummary: options.takeoverDeliverySummary,
    summaryMode: options.summaryMode,
    sampleSummary: collector.sampleSummary(),
    ...(takeoverDelivery ? { takeoverDelivery } : {}),
  };
```

- [ ] **Step 5: Run focused tests and syntax checks**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
node --check scripts/load-chat.mjs
```

Expected: PASS and syntax check success.

- [ ] **Step 6: Commit**

```bash
git add scripts/load-chat.mjs scripts/lib/loadChatPlan.mjs scripts/lib/loadChatPlan.test.mjs
git commit -m "feat: add count-only load chat summary mode"
```

---

### Task 5: Load Runner Step Diagnostics

**Files:**
- Modify: `scripts/lib/loadChatPlan.test.mjs`
- Modify: `scripts/lib/loadChatPlan.mjs`
- Modify: `scripts/load-chat.mjs`

- [ ] **Step 1: Write failing tests for diagnostic formatting**

Add these imports in `scripts/lib/loadChatPlan.test.mjs`.

```js
  formatLoadStepError,
  redactSensitiveUrl,
```

Add tests.

```js
test('redactSensitiveUrl removes ticket and token query values', () => {
  assert.equal(
    redactSensitiveUrl('ws://localhost/api/ws/chat?ticket=secret&token=session&room=1'),
    'ws://localhost/api/ws/chat?ticket=%5Bredacted%5D&token=%5Bredacted%5D&room=1',
  );
});

test('formatLoadStepError includes label viewer step method and redacted URL', () => {
  const error = formatLoadStepError({
    label: '5k',
    viewerIndex: 5104,
    step: 'issue-ticket',
    method: 'POST',
    url: 'http://localhost/api/ws-tickets?token=secret',
    cause: new Error('fetch failed'),
  });

  assert.match(error, /^\[5k\] viewer 5104 issue-ticket POST http:\/\/localhost\/api\/ws-tickets/);
  assert.match(error, /failed: fetch failed$/);
  assert.doesNotMatch(error, /secret/);
});

test('formatLoadStepError formats sender steps without viewer index', () => {
  const error = formatLoadStepError({
    label: '1k',
    step: 'create-room',
    method: 'POST',
    url: 'http://localhost/api/chat-rooms',
    cause: new Error('HTTP 502 bad gateway'),
  });

  assert.equal(error, '[1k] create-room POST http://localhost/api/chat-rooms failed: HTTP 502 bad gateway');
});
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
```

Expected: FAIL with missing diagnostic helper exports.

- [ ] **Step 3: Implement diagnostic helpers**

Add to `scripts/lib/loadChatPlan.mjs`.

```js
export function redactSensitiveUrl(value) {
  const url = new URL(value.toString());
  for (const name of ['ticket', 'token']) {
    if (url.searchParams.has(name)) {
      url.searchParams.set(name, '[redacted]');
    }
  }
  return url.toString();
}

export function formatLoadStepError({ label, viewerIndex, step, method, url, cause }) {
  const prefix = label ? `[${label}] ` : '';
  const actor = viewerIndex === undefined ? '' : `viewer ${viewerIndex} `;
  return `${prefix}${actor}${step} ${method} ${redactSensitiveUrl(url)} failed: ${cause.message}`;
}
```

- [ ] **Step 4: Wrap HTTP and WebSocket preparation steps**

Update `scripts/load-chat.mjs` imports.

```js
  formatLoadStepError,
  redactSensitiveUrl,
```

Remove the local `redactSensitiveUrl` function at the bottom of `scripts/load-chat.mjs`.

Add a helper near `requestJson`.

```js
async function withLoadStep(context, operation) {
  try {
    return await operation();
  } catch (error) {
    throw new Error(formatLoadStepError({ ...context, cause: error }), { cause: error });
  }
}
```

Change `requestJson` signature and error wrapping.

```js
async function requestJson(path, options = {}, context = {}) {
  const base = new URL(httpBaseUrl);
  const basePath = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
  const normalizedPath = path.startsWith('/') ? path.slice(1) : path;
  const url = new URL(`${basePath}${normalizedPath}`, base.origin);
  return withLoadStep({
    label: options.label,
    method: options.method ?? 'GET',
    url,
    ...context,
  }, async () => {
    const response = await fetch(url, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers ?? {}),
      },
    });
    return readJsonResponse(response, { method: options.method ?? 'GET', url });
  });
}
```

Update the helper functions that call `requestJson` so they accept a context object.

```js
async function registerUser(prefix, context = {}) {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000).toString(36)}`;
  const username = buildLoadUsername(prefix, suffix);
  const password = 'password';
  const user = await requestJson('/users/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName: username }),
  }, context);
  return { ...user, password };
}

async function loginUser(user, context = {}) {
  return requestJson('/users/login', {
    method: 'POST',
    body: JSON.stringify({ username: user.username, password: user.password }),
  }, context);
}

async function createRoom(namePrefix, login, context = {}) {
  return requestJson('/chat-rooms', {
    method: 'POST',
    headers: authorizationHeaders(login),
    body: JSON.stringify({
      name: `${namePrefix}-${Date.now()}`,
      description: 'phase 6 load verification room',
      type: 'GROUP',
      imageUrl: null,
      maxMembers: 20000,
    }),
  }, context);
}

async function issueWebSocketTicket(login, context = {}) {
  return requestJson('/ws-tickets', {
    method: 'POST',
    headers: authorizationHeaders(login),
  }, context);
}
```

When calling setup functions from `main`, pass explicit step context. For example:

```js
  const sender = await registerUser('load_sender', { label: options.label, step: 'register-sender' });
  const senderLogin = await loginUser(sender, { label: options.label, step: 'login-sender' });
  const room = await createRoom(`load-${options.room}`, senderLogin, { label: options.label, step: 'create-room' });
```

In the viewer loop, pass viewer-specific step context.

```js
    const context = { label: options.label, viewerIndex: index };
    const user = await registerUser(`load_viewer_${index}`, { ...context, step: 'register-viewer' });
    const login = await loginUser(user, { ...context, step: 'login-viewer' });
    await requestJson(`/chat-rooms/${room.id}/members`, {
      method: 'POST',
      headers: authorizationHeaders(login),
    }, { ...context, step: 'join-room' });
```

Change `connectSession` to accept context and wrap ticket/handshake.

```js
async function connectSession(login, onJson, context = {}) {
  const ticket = await issueWebSocketTicket(login, { ...context, step: 'issue-ticket' });
  const url = new URL(wsBaseUrl);
  url.searchParams.set('ticket', ticket.ticket);
  const ws = new RawWebSocket(url.toString(), onJson);
  await withLoadStep({
    ...context,
    step: context.step === 'connect-sender' ? 'connect-sender' : 'websocket-handshake',
    method: 'GET',
    url,
  }, () => ws.connect());
  return ws;
}
```

Update the sender WebSocket connection call.

```js
  const senderWs = await connectSession(senderLogin, undefined, {
    label: options.label,
    step: 'connect-sender',
  });
```

- [ ] **Step 5: Run focused tests and syntax checks**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
node --check scripts/load-chat.mjs
```

Expected: PASS and syntax check success.

- [ ] **Step 6: Commit**

```bash
git add scripts/load-chat.mjs scripts/lib/loadChatPlan.mjs scripts/lib/loadChatPlan.test.mjs
git commit -m "feat: add load chat stage diagnostics"
```

---

### Task 6: Nginx Connection Budget

**Files:**
- Modify: `scripts/lib/dockerComposeWorkerScale.test.mjs`
- Create: `infra/nginx/nginx.main.conf.template`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Write failing config tests**

Update the top of `scripts/lib/dockerComposeWorkerScale.test.mjs`.

```js
const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8');
const envExample = readFileSync(new URL('../../.env.example', import.meta.url), 'utf8');
const nginxMainConfig = readFileSync(new URL('../../infra/nginx/nginx.main.conf.template', import.meta.url), 'utf8');
```

Add tests.

```js
test('nginx main config exposes worker connection budget through envsubst', () => {
  assert.match(nginxMainConfig, /worker_processes \$\{NGINX_WORKER_PROCESSES\};/);
  assert.match(nginxMainConfig, /worker_connections \$\{NGINX_WORKER_CONNECTIONS\};/);
  assert.match(nginxMainConfig, /include \/etc\/nginx\/conf\.d\/\*\.conf;/);
});

test('compose gives nginx nofile and worker connection budget for staged gate', () => {
  const nginx = serviceBlock('nginx');

  assert.match(nginx, /NGINX_WORKER_PROCESSES: \$\{NGINX_WORKER_PROCESSES:-auto\}/);
  assert.match(nginx, /NGINX_WORKER_CONNECTIONS: \$\{NGINX_WORKER_CONNECTIONS:-20000\}/);
  assert.match(nginx, /\.\/infra\/nginx\/nginx\.main\.conf\.template:\/etc\/nginx\/nginx\.conf\.template:ro/);
  assert.match(nginx, /ulimits:/);
  assert.match(nginx, /nofile:/);
  assert.match(nginx, /soft: \$\{NGINX_NOFILE_SOFT:-65535\}/);
  assert.match(nginx, /hard: \$\{NGINX_NOFILE_HARD:-65535\}/);
});
```

- [ ] **Step 2: Run the config tests and verify they fail**

Run:

```bash
node --test scripts/lib/dockerComposeWorkerScale.test.mjs
```

Expected: FAIL because `infra/nginx/nginx.main.conf.template` does not exist or compose budget fields are missing.

- [ ] **Step 3: Add nginx main config template**

Create `infra/nginx/nginx.main.conf.template`.

```nginx
user nginx;
worker_processes ${NGINX_WORKER_PROCESSES};

error_log /var/log/nginx/error.log notice;
pid /var/run/nginx.pid;

events {
    worker_connections ${NGINX_WORKER_CONNECTIONS};
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    sendfile on;
    tcp_nopush on;
    keepalive_timeout 65;

    include /etc/nginx/conf.d/*.conf;
}
```

- [ ] **Step 4: Wire the template and ulimit into compose nginx service**

In the `nginx` service of `docker-compose.yml`, add env values.

```yaml
      NGINX_WORKER_PROCESSES: ${NGINX_WORKER_PROCESSES:-auto}
      NGINX_WORKER_CONNECTIONS: ${NGINX_WORKER_CONNECTIONS:-20000}
```

Add the main config template volume beside the existing default config template.

```yaml
      - ./infra/nginx/nginx.main.conf.template:/etc/nginx/nginx.conf.template:ro
      - ./infra/nginx/nginx.conf:/etc/nginx/templates/default.conf.template:ro
```

Add a command that renders only the main config template before starting nginx. Keep the official entrypoint behavior for `default.conf.template`.

```yaml
    command:
      - /bin/sh
      - -c
      - envsubst '$$NGINX_WORKER_PROCESSES $$NGINX_WORKER_CONNECTIONS' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf && exec nginx -g 'daemon off;'
```

Add nofile ulimit.

```yaml
    ulimits:
      nofile:
        soft: ${NGINX_NOFILE_SOFT:-65535}
        hard: ${NGINX_NOFILE_HARD:-65535}
```

- [ ] **Step 5: Run config tests and compose render check**

Run:

```bash
node --test scripts/lib/dockerComposeWorkerScale.test.mjs
docker compose --profile cluster config >/tmp/chat-compose-config.yml
rg "NGINX_WORKER_CONNECTIONS|nginx.main.conf.template|nofile" /tmp/chat-compose-config.yml
```

Expected: Node test PASS and rendered compose output contains `NGINX_WORKER_CONNECTIONS`, `nginx.main.conf.template`, and `nofile`.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml infra/nginx/nginx.main.conf.template scripts/lib/dockerComposeWorkerScale.test.mjs
git commit -m "feat: raise nginx connection budget for staged gate"
```

---

### Task 7: Documentation and Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/infrastructure.md`

- [ ] **Step 1: Update README staged gate instructions**

Replace the Phase 8.4 section in `README.md` with the following text:

Phase 8.4 hot room shard 분산 staged release gate:

```bash
CHAT_PROMETHEUS_URL=http://localhost:9090 node scripts/phase8-hot-room-release-gate.mjs
```

기본 gate는 `1k -> 3k -> 5k -> 7k -> 10k` 순서로 실행하며, 어느 단계까지 통과했는지 JSON으로 출력합니다. 병목 구간을 다르게 좁히려면 `--stages 1000,2000,4000,8000,10000`처럼 stage 목록을 지정합니다. 기존 10k 단일 실행은 `--single-stage --viewers 10000 --messages-per-sec 10000`로 실행합니다.

10,000 viewer stage를 실행할 때는 backend 시작 전 `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP`를 최대 viewer 수 이상으로 설정해야 합니다. nginx staged gate 예산은 `NGINX_WORKER_CONNECTIONS`, `NGINX_NOFILE_SOFT`, `NGINX_NOFILE_HARD`로 조정합니다.

- [ ] **Step 2: Update infrastructure docs**

Replace the Phase 8.4 release gate paragraph in `docs/infrastructure.md` with the following text:

staged release gate는 다음 명령으로 실행한다.

```bash
node scripts/phase8-hot-room-release-gate.mjs
```

이 명령은 `1k`, `3k`, `5k`, `7k`, `10k` stage를 fail-fast로 실행한다. 각 stage는 viewer 수와 messages/sec를 같은 값으로 두고 60초 동안 부하를 만든 뒤, Prometheus에서 fanout p95, stream shard 관측 수, Redis Streams group lag를 조회해 threshold를 넘으면 실패한다. 실패하면 이후 stage는 실행하지 않고 JSON artifact에 `lastPassedStage`, `failedStage`, stage별 load summary, Prometheus snapshot, load runner stderr를 남긴다.

custom stage는 다음처럼 지정한다.

```bash
node scripts/phase8-hot-room-release-gate.mjs --stages 1000,2000,4000,8000,10000
```

기존 10k 단일 gate 호환 모드는 다음처럼 실행한다.

```bash
node scripts/phase8-hot-room-release-gate.mjs --single-stage --viewers 10000 --messages-per-sec 10000
```

- [ ] **Step 3: Run all focused Node tests**

Run:

```bash
node --test \
  scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs \
  scripts/lib/phase8HotRoomReleaseGateRunner.test.mjs \
  scripts/lib/loadChatPlan.test.mjs \
  scripts/lib/dockerComposeWorkerScale.test.mjs
```

Expected: PASS.

- [ ] **Step 4: Run syntax checks**

Run:

```bash
node --check scripts/phase8-hot-room-release-gate.mjs
node --check scripts/load-chat.mjs
node --check scripts/lib/phase8HotRoomReleaseGatePlan.mjs
node --check scripts/lib/phase8HotRoomReleaseGateRunner.mjs
node --check scripts/lib/loadChatPlan.mjs
```

Expected: all syntax checks pass.

- [ ] **Step 5: Run compose render validation**

Run:

```bash
docker compose --profile cluster config >/tmp/chat-compose-config.yml
rg "NGINX_WORKER_CONNECTIONS|soft: 65535|hard: 65535|nginx.main.conf.template|CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT" /tmp/chat-compose-config.yml
```

Expected: all searched terms appear in rendered compose output.

- [ ] **Step 6: Commit docs and verification wiring**

```bash
git add README.md docs/infrastructure.md
git commit -m "docs: document staged phase8 release gate"
```

---

## 최종 검증

- [ ] Run all Phase 8 Node tests:

```bash
node --test scripts/lib/phase8*.test.mjs scripts/lib/loadChatPlan.test.mjs scripts/lib/dockerComposeWorkerScale.test.mjs
```

- [ ] Run full syntax checks for changed scripts:

```bash
node --check scripts/phase8-hot-room-release-gate.mjs
node --check scripts/load-chat.mjs
```

- [ ] Run Gradle tests only if Kotlin code changed. This plan should not change Kotlin code, so Gradle is not required unless implementation expands scope.

- [ ] Run compose render check:

```bash
docker compose --profile cluster config >/tmp/chat-compose-config.yml
rg "NGINX_WORKER_CONNECTIONS|nofile|summary-mode|phase8-hot-room-release-gate" /tmp/chat-compose-config.yml README.md docs/infrastructure.md
```

- [ ] Optional running-stack smoke after code-level tests pass:

```bash
CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP=20000 \
CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_USER=20 \
CHAT_PROMETHEUS_URL=http://localhost:9090 \
node scripts/phase8-hot-room-release-gate.mjs --stages 1000
```

Expected: JSON stdout with `ok: true`, `lastPassedStage: "1k"`, and one stage result. If the local stack is not running, record that the running-stack smoke was not executed.

---

## 자체 검토 체크리스트

- 설계 요구사항 매핑:
  - `1k -> 3k -> 5k -> 7k -> 10k` 기본 stage: Task 1
  - custom `--stages`: Task 1
  - `--single-stage` 호환 모드: Task 1
  - fail-fast JSON result: Task 2, Task 3
  - load runner stderr 보존: Task 3
  - count-only memory profile: Task 4
  - step-level diagnostics: Task 5
  - nginx connection budget: Task 6
  - docs update: Task 7
- 금지 표현 스캔: 이 문서에는 미완성 작업을 뜻하는 예약 문구를 남기지 않는다.
- type consistency:
  - stage object는 `{ name, viewers, messagesPerSec, durationSeconds }` 형태로 통일한다.
  - gate result는 `{ ok, mode, lastPassedStage, failedStage, stages, thresholds }` 형태로 통일한다.
  - load summary는 기존 `receivedPerViewer` 필드를 유지해 gate assertion과 호환한다.
