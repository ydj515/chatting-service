import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildChaosScenario,
  buildInjectCommands,
  evaluateRecoveryCheck,
  exitCodeForChaosSummary,
  parseChaosArgs,
  summarizeChaosRun,
} from './phase7ChaosPlan.mjs';

test('parseChaosArgs defaults to dry-run with env-backed endpoints', () => {
  const options = parseChaosArgs(['--scenario', 'worker-kill'], {
    CHAT_PHASE7_BASE_URL: 'http://localhost:8088/',
    CHAT_PHASE7_METRICS_URL: 'http://localhost:8088/actuator/prometheus',
  });

  assert.equal(options.scenario, 'worker-kill');
  assert.equal(options.execute, false);
  assert.equal(options.restore, true);
  assert.equal(options.baseUrl, 'http://localhost:8088');
  assert.equal(options.metricsUrl, 'http://localhost:8088/actuator/prometheus');
  // worker-kill 시나리오 기본 required check
  assert.deepEqual(options.checks, ['health', 'functional', 'lag']);
});

test('parseChaosArgs falls back to the scenario default checks (lag excluded for gateway)', () => {
  const options = parseChaosArgs(['--scenario', 'gateway-kill'], {});
  assert.deepEqual(options.checks, ['health', 'functional']);
});

test('parseChaosArgs maps CLI flags over defaults', () => {
  const options = parseChaosArgs(
    [
      '--scenario', 'redis-restart',
      '--target', 'redis',
      '--execute',
      '--no-restore',
      '--recovery-timeout-ms', '20000',
      '--scenario-slo-ms', '25000',
      '--checks', 'health,lag',
      '--lag-threshold', '50',
      '--pending-threshold', '10',
      '--json',
    ],
    {},
  );

  assert.equal(options.execute, true);
  assert.equal(options.restore, false);
  assert.equal(options.target, 'redis');
  assert.equal(options.recoveryTimeoutMs, 20000);
  assert.equal(options.scenarioSloMs, 25000);
  assert.deepEqual(options.checks, ['health', 'lag']);
  assert.equal(options.lagThreshold, 50);
  assert.equal(options.pendingThreshold, 10);
  assert.equal(options.json, true);
});

test('parseChaosArgs rejects unknown scenario and unknown check', () => {
  assert.throws(() => parseChaosArgs(['--scenario', 'nope'], {}), /scenario/);
  assert.throws(() => parseChaosArgs(['--scenario', 'worker-kill', '--checks', 'health,bogus'], {}), /check/);
  assert.throws(() => parseChaosArgs(['--scenario', 'worker-kill', '--oops', 'x'], {}), /Unknown argument/);
});

test('parseChaosArgs requires a scenario', () => {
  assert.throws(() => parseChaosArgs([], {}), /scenario is required/);
});

test('buildChaosScenario maps each known scenario to a default target and inject action', () => {
  assert.equal(buildChaosScenario({ scenario: 'gateway-kill' }).target, 'chat-websocket-app-1');
  assert.equal(buildChaosScenario({ scenario: 'gateway-kill' }).injectAction, 'kill');
  assert.equal(buildChaosScenario({ scenario: 'worker-kill' }).target, 'chat-worker-app-1');
  assert.equal(buildChaosScenario({ scenario: 'redis-restart' }).target, 'redis');
  assert.equal(buildChaosScenario({ scenario: 'redis-restart' }).injectAction, 'restart');
  assert.equal(buildChaosScenario({ scenario: 'replica-kill' }).target, 'postgres-replica');
});

test('buildChaosScenario exposes required checks per scenario', () => {
  assert.deepEqual(buildChaosScenario({ scenario: 'worker-kill' }).requiredChecks, ['health', 'functional', 'lag']);
  assert.deepEqual(buildChaosScenario({ scenario: 'gateway-kill' }).requiredChecks, ['health', 'functional']);
  assert.deepEqual(buildChaosScenario({ scenario: 'replica-kill' }).requiredChecks, ['health', 'functional']);
});

test('buildChaosScenario honors target and slo overrides', () => {
  const scenario = buildChaosScenario({ scenario: 'worker-kill', target: 'chat-worker-app-2', scenarioSloMs: 99000 });
  assert.equal(scenario.target, 'chat-worker-app-2');
  assert.equal(scenario.sloMs, 99000);
});

test('buildInjectCommands returns kill and restore for kill scenarios', () => {
  const scenario = buildChaosScenario({ scenario: 'worker-kill' });
  const commands = buildInjectCommands(scenario, 'abc123def456', { restore: true });
  assert.deepEqual(commands.inject, ['kill', 'abc123def456']);
  assert.deepEqual(commands.restore, ['start', 'abc123def456']);
});

test('buildInjectCommands uses restart and no restore for restart scenarios', () => {
  const scenario = buildChaosScenario({ scenario: 'redis-restart' });
  const commands = buildInjectCommands(scenario, 'redis001', { restore: true });
  assert.deepEqual(commands.inject, ['restart', 'redis001']);
  assert.equal(commands.restore, null);
});

test('buildInjectCommands omits restore when restore is disabled', () => {
  const scenario = buildChaosScenario({ scenario: 'worker-kill' });
  const commands = buildInjectCommands(scenario, 'abc', { restore: false });
  assert.equal(commands.restore, null);
});

test('evaluateRecoveryCheck reports recovered with elapsed time', () => {
  const result = evaluateRecoveryCheck(
    { check: 'health', required: true },
    { recovered: true, elapsedMs: 4200 },
  );
  assert.deepEqual(result, { check: 'health', required: true, recovered: true, recoveryMs: 4200 });
});

test('evaluateRecoveryCheck reports lag failure with last observed value', () => {
  const result = evaluateRecoveryCheck(
    { check: 'lag', required: true },
    { recovered: false, elapsedMs: null, lastValue: 142 },
  );
  assert.deepEqual(result, {
    check: 'lag',
    required: true,
    recovered: false,
    recoveryMs: null,
    lastValue: 142,
  });
});

test('summarizeChaosRun marks release blocking when a required check fails', () => {
  const summary = summarizeChaosRun({
    scenario: 'worker-kill',
    injectedContainer: 'chat-worker-app-1',
    dryRun: false,
    sloMs: 30000,
    totalRecoveryMs: 18200,
    checks: [
      { check: 'health', required: true, recovered: true, recoveryMs: 4200 },
      { check: 'functional', required: true, recovered: true, recoveryMs: 9100 },
      { check: 'lag', required: true, recovered: false, recoveryMs: null, lastValue: 142 },
    ],
  });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.recoverySloMet, true);
  // 부분 복구를 개별로 확인할 수 있어야 한다.
  assert.equal(summary.checks.find((c) => c.check === 'health').recovered, true);
  assert.equal(summary.checks.find((c) => c.check === 'lag').recovered, false);
});

test('summarizeChaosRun is non-blocking when all required checks recover within SLO', () => {
  const summary = summarizeChaosRun({
    scenario: 'gateway-kill',
    injectedContainer: 'chat-websocket-app-1',
    dryRun: false,
    sloMs: 30000,
    totalRecoveryMs: 12000,
    checks: [
      { check: 'health', required: true, recovered: true, recoveryMs: 5000 },
      { check: 'functional', required: true, recovered: true, recoveryMs: 12000 },
    ],
  });

  assert.equal(summary.releaseBlocking, false);
  assert.equal(summary.recoverySloMet, true);
});

test('summarizeChaosRun is release blocking when SLO is exceeded even if checks recover', () => {
  const summary = summarizeChaosRun({
    scenario: 'redis-restart',
    injectedContainer: 'redis',
    dryRun: false,
    sloMs: 10000,
    totalRecoveryMs: 14000,
    checks: [{ check: 'health', required: true, recovered: true, recoveryMs: 14000 }],
  });

  assert.equal(summary.recoverySloMet, false);
  assert.equal(summary.releaseBlocking, true);
});

test('summarizeChaosRun ignores optional check failures for the gate', () => {
  const summary = summarizeChaosRun({
    scenario: 'replica-kill',
    injectedContainer: 'postgres-replica',
    dryRun: false,
    sloMs: 30000,
    totalRecoveryMs: 8000,
    checks: [
      { check: 'health', required: true, recovered: true, recoveryMs: 8000 },
      { check: 'lag', required: false, recovered: false, recoveryMs: null, lastValue: 3 },
    ],
  });

  assert.equal(summary.releaseBlocking, false);
});

test('summarizeChaosRun dry-run is never release blocking', () => {
  const summary = summarizeChaosRun({
    scenario: 'worker-kill',
    injectedContainer: 'chat-worker-app-1',
    dryRun: true,
    sloMs: 30000,
    totalRecoveryMs: null,
    checks: [],
  });

  assert.equal(summary.dryRun, true);
  assert.equal(summary.releaseBlocking, false);
});

test('exitCodeForChaosSummary returns 0 for non-blocking and 1 for blocking', () => {
  assert.equal(exitCodeForChaosSummary({ dryRun: false, releaseBlocking: false }), 0);
  assert.equal(exitCodeForChaosSummary({ dryRun: false, releaseBlocking: true }), 1);
  assert.equal(exitCodeForChaosSummary({ dryRun: true, releaseBlocking: false }), 0);
});
