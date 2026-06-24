#!/usr/bin/env node
// Phase 7 reconnect chaos orchestrator.
// 순수 로직은 ./lib/phase7ReconnectChaosPlan.mjs에 있고, 이 파일은 gateway 컨테이너 resolve,
// reconnect-load child spawn, ready-file 타이밍 주입, 복구만 담당한다.
// 기본은 dry-run(계획만 출력)이고, --execute일 때만 실제로 gateway를 restart/kill한다.
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildGatewayFaultPlan,
  buildReconnectLoadArgs,
  exitCodeForReconnectChaosSummary,
  parseReconnectChaosArgs,
  summarizeReconnectChaos,
} from './lib/phase7ReconnectChaosPlan.mjs';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const DOCKER_TIMEOUT_MS = Number(process.env.CHAT_PHASE7_CHAOS_DOCKER_TIMEOUT_MS ?? 30000);
const READY_POLL_INTERVAL_MS = Number(process.env.CHAT_PHASE7_RECONNECT_CHAOS_READY_POLL_MS ?? 250);

async function main() {
  let options;
  try {
    options = parseReconnectChaosArgs(process.argv.slice(2));
  } catch (error) {
    // 사용법/인자 오류는 exit code 2로 구분한다(런타임 실패는 1).
    console.error(error.message);
    process.exitCode = 2;
    return;
  }

  const faultPlan = buildGatewayFaultPlan(options);
  const reconnectArgs = buildReconnectLoadArgs(options);

  if (!options.execute) {
    // dry-run: 실제 docker 명령이나 storm을 실행하지 않고 계획만 보여준다. 라이브 스택을 요구하지 않는다.
    const summary = summarizeReconnectChaos({
      faultMode: options.faultMode,
      injectedContainers: options.gateways,
      injectionOffsetMs: null,
      recoveryElapsedMs: null,
      maxRecoverySloMs: options.maxRecoverySloMs,
      reconnectSummary: null,
      dryRun: true,
    });
    printSummary({ ...summary, plan: { fault: faultPlan, reconnectLoadArgs: reconnectArgs } }, options);
    process.exitCode = exitCodeForReconnectChaosSummary(summary);
    return;
  }

  // 주입/복구에 쓸 컨테이너 id를 먼저 모은다. restart는 id가 유지되고, kill은 복구 시 같은 id를 start한다.
  const containerIds = new Map(options.gateways.map((service) => [service, resolveContainerId(service)]));
  const readyFile = path.join(os.tmpdir(), `phase7-reconnect-chaos-ready-${Date.now()}-${process.pid}.json`);
  const loadScript = fileURLToPath(new URL('./phase7-reconnect-load.mjs', import.meta.url));

  const killedContainers = [];
  let primaryError = null;
  let stdout = '';
  let recoveryElapsedMs = null;

  const child = spawn(process.execPath, [loadScript, ...reconnectArgs, '--ready-file', readyFile], {
    env: process.env,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  const childExit = waitForExit(child);
  child.stdout.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    stdout += text;
    process.stderr.write(text);
  });

  let injectionOffsetMs = null;
  try {
    const readyAt = await waitForReady(readyFile, options.readyTimeoutMs);
    await sleep(options.injectAfterMs);
    const injectionStart = Date.now();
    injectionOffsetMs = injectionStart - readyAt;

    for (let stepIndex = 0; stepIndex < faultPlan.length; stepIndex += 1) {
      const step = faultPlan[stepIndex];
      const containerId = containerIds.get(step.service);
      // 전체 gateway가 동시에 내려가지 않도록, 첫 step 이후에는 rolling 지연을 둔다.
      if (stepIndex > 0 && options.rollingStepDelayMs > 0) {
        await sleep(options.rollingStepDelayMs);
      }
      console.error(`[reconnect-chaos] ${step.action} ${step.service} (${containerId.slice(0, 12)})`);
      runDocker([step.action, containerId]);
      if (step.restoreNeeded) {
        killedContainers.push({ service: step.service, id: containerId });
      }
    }

    const exitCode = await childExit;
    recoveryElapsedMs = Date.now() - injectionStart;
    const reconnectSummary = parseLastJson(stdout);
    if (reconnectSummary === null) {
      throw new Error(`reconnect-load produced no JSON summary (exit ${exitCode})`);
    }

    const summary = summarizeReconnectChaos({
      faultMode: options.faultMode,
      injectedContainers: faultPlan.map((step) => step.service),
      injectionOffsetMs,
      recoveryElapsedMs,
      maxRecoverySloMs: options.maxRecoverySloMs,
      reconnectSummary,
      dryRun: false,
    });
    printSummary(summary, options);
    process.exitCode = exitCodeForReconnectChaosSummary(summary);
  } catch (error) {
    primaryError = error;
  } finally {
    if (child.exitCode === null) {
      child.kill('SIGTERM');
    }
    for (const killed of killedContainers) {
      // hard-kill로 정지된 컨테이너를 되살린다. restart 모드는 컨테이너를 이미 되살렸으므로 대상이 아니다.
      console.error(`[reconnect-chaos] restoring ${killed.service} (${killed.id.slice(0, 12)})`);
      try {
        runDocker(['start', killed.id]);
      } catch (restoreError) {
        console.error(`[reconnect-chaos] restore failed for ${killed.service}: ${restoreError.message}`);
      }
    }
    await fs.rm(readyFile, { force: true });
  }

  if (primaryError) {
    throw primaryError;
  }
}

async function waitForReady(readyFile, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      await fs.access(readyFile);
      return Date.now();
    } catch {
      await sleep(READY_POLL_INTERVAL_MS);
    }
  }
  throw new Error(`Timed out waiting for reconnect storm ready signal: ${readyFile}`);
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

function waitForExit(child) {
  return new Promise((resolve) => {
    child.once('exit', (code) => resolve(code ?? 1));
  });
}

function parseLastJson(text) {
  // reconnect-load는 마지막에 pretty JSON summary를 stdout으로 출력한다.
  const end = text.lastIndexOf('}');
  if (end === -1) {
    return null;
  }
  for (let start = text.lastIndexOf('{', end); start !== -1; start = text.lastIndexOf('{', start - 1)) {
    try {
      return JSON.parse(text.slice(start, end + 1));
    } catch {
      // outer JSON object를 찾을 때까지 왼쪽으로 계속 이동한다.
    }
  }
  return null;
}

function printSummary(summary, options) {
  if (options.json) {
    console.log(JSON.stringify(summary));
  } else {
    console.log(JSON.stringify(summary, null, 2));
  }
}

main().catch((error) => {
  // 인자 파싱 이후의 런타임 실패(docker/child 등)는 exit code 1로 본다.
  console.error(error.message);
  process.exitCode = 1;
});
