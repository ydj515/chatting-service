#!/usr/bin/env node
// Phase 7 chaos test runner.
// 순수 로직은 ./lib/phase7ChaosPlan.mjs에 있고, 이 파일은 docker/HTTP I/O와 복구 폴링만 담당한다.
// 기본은 dry-run(계획만 출력)이고, --execute일 때만 실제로 컨테이너를 kill/restart한다.
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  buildChaosScenario,
  buildInjectCommands,
  evaluateRecoveryCheck,
  exitCodeForChaosSummary,
  parseChaosArgs,
  summarizeChaosRun,
} from './lib/phase7ChaosPlan.mjs';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const POLL_INTERVAL_MS = Number(process.env.CHAT_PHASE7_CHAOS_POLL_INTERVAL_MS ?? 1000);
const DOCKER_TIMEOUT_MS = Number(process.env.CHAT_PHASE7_CHAOS_DOCKER_TIMEOUT_MS ?? 30000);

async function main() {
  let options;
  try {
    options = parseChaosArgs(process.argv.slice(2));
  } catch (error) {
    // 사용법/인자 오류는 exit code 2로 구분한다(런타임 실패는 1).
    console.error(error.message);
    process.exitCode = 2;
    return;
  }
  const scenario = buildChaosScenario(options);

  if (!options.execute) {
    // dry-run: 실제 docker 명령을 실행하지 않고 계획만 보여준다. 라이브 스택을 요구하지 않는다.
    const planCommands = buildInjectCommands(scenario, `<${scenario.target}>`, { restore: options.restore });
    const summary = summarizeChaosRun({
      scenario: scenario.name,
      injectedContainer: scenario.target,
      dryRun: true,
      sloMs: scenario.sloMs,
      totalRecoveryMs: null,
      checks: [],
    });
    printSummary(
      { ...summary, plan: { inject: planCommands.inject, restore: planCommands.restore, checks: options.checks } },
      options,
    );
    process.exitCode = exitCodeForChaosSummary(summary);
    return;
  }

  const containerId = resolveContainerId(scenario.target);
  const commands = buildInjectCommands(scenario, containerId, { restore: options.restore });
  console.error(`[chaos] injecting ${scenario.injectAction} on ${scenario.target} (${containerId.slice(0, 12)})`);
  runDocker(commands.inject);
  if (commands.restore) {
    console.error(`[chaos] restoring container ${containerId.slice(0, 12)}`);
    runDocker(commands.restore);
  }

  const startedAt = Date.now();
  const checkResults = await pollRecovery({ options, scenario, startedAt });
  const recoveredRequired = checkResults.filter((result) => result.required && result.recovered);
  const allRequiredRecovered = checkResults.every((result) => !result.required || result.recovered);
  const totalRecoveryMs = allRequiredRecovered
    ? recoveredRequired.reduce((max, result) => Math.max(max, result.recoveryMs ?? 0), 0)
    : Date.now() - startedAt;

  const summary = summarizeChaosRun({
    scenario: scenario.name,
    injectedContainer: scenario.target,
    dryRun: false,
    sloMs: scenario.sloMs,
    totalRecoveryMs,
    checks: checkResults,
  });
  printSummary(summary, options);
  process.exitCode = exitCodeForChaosSummary(summary);
}

async function pollRecovery({ options, scenario, startedAt }) {
  // required 여부는 시나리오 정의를 따른다. 사용자가 --checks로 추가한 비관련 체크는
  // optional로 두어 release gate를 블로킹하지 않는다.
  const pending = new Map(
    options.checks.map((check) => [
      check,
      { check, required: scenario.requiredChecks.includes(check), recovered: false, lastValue: undefined },
    ]),
  );
  const deadline = startedAt + options.recoveryTimeoutMs;

  while (Date.now() < deadline && [...pending.values()].some((entry) => !entry.recovered)) {
    // 느린 probe가 다른 probe와 poll 주기를 막지 않도록 병렬로 실행한다.
    await Promise.all(
      [...pending.values()]
        .filter((entry) => !entry.recovered)
        .map(async (entry) => {
          const observation = await probeCheck(entry.check, options, scenario);
          if (observation.lastValue !== undefined) {
            entry.lastValue = observation.lastValue;
          }
          if (observation.recovered) {
            entry.recovered = true;
            entry.elapsedMs = Date.now() - startedAt;
          }
        }),
    );
    if ([...pending.values()].every((entry) => entry.recovered)) {
      break;
    }
    await sleep(POLL_INTERVAL_MS);
  }

  return [...pending.values()].map((entry) =>
    evaluateRecoveryCheck(
      { check: entry.check, required: entry.required },
      { recovered: entry.recovered, elapsedMs: entry.elapsedMs ?? null, lastValue: entry.lastValue },
    ),
  );
}

async function probeCheck(check, options, scenario) {
  try {
    if (check === 'health') {
      return await probeHealth(options);
    }
    if (check === 'lag') {
      return await probeLag(options);
    }
    if (check === 'functional') {
      return await probeFunctional(options);
    }
  } catch (error) {
    console.error(`[chaos] ${check} probe error: ${error.message}`);
  }
  return { recovered: false };
}

async function probeHealth(options) {
  const url = `${options.baseUrl}/api/actuator/health`;
  const body = await fetchJson(url, options.recoveryTimeoutMs);
  return { recovered: body?.status === 'UP' };
}

async function probeLag(options) {
  const url = options.metricsUrl ?? `${options.baseUrl}/api/actuator/prometheus`;
  const text = await fetchText(url, options.recoveryTimeoutMs);
  if (!text) {
    return { recovered: false };
  }
  const lag = maxMetricValue(text, 'chat_redis_stream_group_lag');
  const pending = maxMetricValue(text, 'chat_redis_stream_group_pending');
  // metric 자체가 없으면(컨테이너 다운, 502 HTML 등) 0으로 오판하지 않고 복구 실패로 본다.
  if (lag === null || pending === null) {
    return { recovered: false };
  }
  const lagOk = lag <= options.lagThreshold;
  const pendingOk = pending <= options.pendingThreshold;
  return { recovered: lagOk && pendingOk, lastValue: Math.max(lag, pending) };
}

async function probeFunctional(options) {
  // 기존 verify-chat.mjs를 synthetic 메시지 송신→수신 probe로 재사용한다.
  const script = fileURLToPath(new URL('./verify-chat.mjs', import.meta.url));
  const status = await runScriptStatus(
    process.execPath,
    [script],
    {
      ...process.env,
      CHAT_HTTP_URL: process.env.CHAT_HTTP_URL ?? `${options.baseUrl}/api`,
    },
    options.recoveryTimeoutMs,
  );
  return { recovered: status === 0 };
}

function maxMetricValue(prometheusText, metricName) {
  let max = 0;
  let found = false;
  for (const line of prometheusText.split(/\r?\n/)) {
    if (!line.startsWith(metricName)) {
      continue;
    }
    const value = Number(line.slice(line.lastIndexOf(' ') + 1));
    if (Number.isFinite(value)) {
      max = found ? Math.max(max, value) : value;
      found = true;
    }
  }
  // metric line이 하나도 없으면 0이 아니라 null을 반환해 복구 오탐을 막는다.
  return found ? max : null;
}

function resolveContainerId(service) {
  const output = execFileSync('docker', ['compose', 'ps', '-q', service], {
    encoding: 'utf8',
    timeout: DOCKER_TIMEOUT_MS,
  });
  const ids = output.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (ids.length === 0) {
    throw new Error(`No running container found for service ${service}`);
  }
  return ids[0];
}

function runDocker(args) {
  execFileSync('docker', args, { stdio: ['ignore', 'inherit', 'inherit'], timeout: DOCKER_TIMEOUT_MS });
}

function runScriptStatus(command, args, env, timeoutMs) {
  // verify-chat는 자체 timeout(CHAT_VERIFY_TIMEOUT_MS)을 갖지만, 자식이 끝나지 않아도
  // poll 주기를 넘기지 않도록 여기서도 상한 timeout으로 강제 종료한다.
  // stderr는 inherit해 functional 실패 원인을 로그로 확인할 수 있게 한다.
  return new Promise((resolve) => {
    const child = spawn(command, args, { env, stdio: ['ignore', 'ignore', 'inherit'] });
    const timer = setTimeout(() => child.kill('SIGTERM'), timeoutMs);
    child.on('exit', (code) => {
      clearTimeout(timer);
      resolve(code ?? 1);
    });
    child.on('error', () => {
      clearTimeout(timer);
      resolve(1);
    });
  });
}

async function fetchJson(url, timeoutMs) {
  const text = await fetchText(url, timeoutMs);
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function fetchText(url, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(timeoutMs, 5000));
  try {
    const response = await fetch(url, { signal: controller.signal });
    return await response.text();
  } finally {
    clearTimeout(timer);
  }
}

function printSummary(summary, options) {
  if (options.json) {
    console.log(JSON.stringify(summary));
  } else {
    console.log(JSON.stringify(summary, null, 2));
  }
}

main().catch((error) => {
  // 인자 파싱 이후의 런타임 실패(docker/probe 등)는 exit code 1로 본다.
  console.error(error.message);
  process.exitCode = 1;
});
