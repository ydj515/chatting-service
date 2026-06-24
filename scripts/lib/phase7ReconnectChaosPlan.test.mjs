import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  KNOWN_FAULT_MODES,
  buildGatewayFaultPlan,
  buildReconnectLoadArgs,
  exitCodeForReconnectChaosSummary,
  parseReconnectChaosArgs,
  summarizeReconnectChaos,
} from './phase7ReconnectChaosPlan.mjs';

test('KNOWN_FAULT_MODES lists the two gateway fault modes', () => {
  assert.deepEqual(KNOWN_FAULT_MODES, ['gateway-rolling-restart', 'gateway-hard-kill']);
});

test('parseReconnectChaosArgs requires a known fault mode', () => {
  assert.throws(() => parseReconnectChaosArgs([]), /--fault is required/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'nope']), /Unknown fault mode/);
});

test('parseReconnectChaosArgs defaults rolling-restart to both replicas and gateway_restart reason', () => {
  const options = parseReconnectChaosArgs(['--fault', 'gateway-rolling-restart']);

  assert.equal(options.faultMode, 'gateway-rolling-restart');
  assert.deepEqual(options.gateways, ['chat-websocket-app-1', 'chat-websocket-app-2']);
  assert.equal(options.execute, false);
  assert.equal(options.restore, true);
  assert.equal(options.rollingStepDelayMs, 2000);
  assert.equal(options.injectAfterMs, 0);
  assert.equal(options.readyTimeoutMs, 60000);
  assert.equal(options.maxRecoverySloMs, 30000);
  assert.equal(options.json, false);
  // reconnect pass-through chaos defaults
  assert.equal(options.clients, 5);
  assert.equal(options.reconnectsPerClient, 6);
  assert.equal(options.attemptSpacingMs, 1000);
  assert.equal(options.jitterMs, 250);
  assert.equal(options.cohort, 'synthetic');
  assert.equal(options.reason, 'gateway_restart');
  assert.equal(options.scenario, 'gateway-rolling-restart');
  // gate ratios default to null so the child runner applies its own defaults
  assert.equal(options.minTicketIssueSuccessRatio, null);
  assert.equal(options.maxCohortFailureRatio, null);
});

test('parseReconnectChaosArgs defaults hard-kill to a single replica and gateway_kill reason', () => {
  const options = parseReconnectChaosArgs(['--fault', 'gateway-hard-kill']);

  assert.deepEqual(options.gateways, ['chat-websocket-app-1']);
  assert.equal(options.reason, 'gateway_kill');
  assert.equal(options.scenario, 'gateway-hard-kill');
});

test('parseReconnectChaosArgs maps orchestration and pass-through overrides', () => {
  const options = parseReconnectChaosArgs([
    '--fault', 'gateway-hard-kill',
    '--gateways', 'chat-websocket-app-1,chat-websocket-app-2',
    '--execute',
    '--no-restore',
    '--rolling-step-delay-ms', '500',
    '--inject-after-ms', '750',
    '--ready-timeout-ms', '30000',
    '--max-recovery-slo-ms', '12000',
    '--clients', '8',
    '--reconnects-per-client', '10',
    '--attempt-spacing-ms', '300',
    '--jitter-ms', '40',
    '--cohort', 'nat_proxy',
    '--reason', 'deploy',
    '--scenario', 'custom-label',
    '--min-ticket-issue-success-ratio', '0.99',
    '--min-handshake-success-ratio', '0.98',
    '--max-rate-limit-failure-ratio', '0.02',
    '--max-cohort-failure-ratio', '0.03',
    '--json',
  ]);

  assert.deepEqual(options.gateways, ['chat-websocket-app-1', 'chat-websocket-app-2']);
  assert.equal(options.execute, true);
  assert.equal(options.restore, false);
  assert.equal(options.rollingStepDelayMs, 500);
  assert.equal(options.injectAfterMs, 750);
  assert.equal(options.readyTimeoutMs, 30000);
  assert.equal(options.maxRecoverySloMs, 12000);
  assert.equal(options.clients, 8);
  assert.equal(options.reconnectsPerClient, 10);
  assert.equal(options.attemptSpacingMs, 300);
  assert.equal(options.jitterMs, 40);
  assert.equal(options.cohort, 'nat_proxy');
  assert.equal(options.reason, 'deploy');
  assert.equal(options.scenario, 'custom-label');
  assert.equal(options.minTicketIssueSuccessRatio, 0.99);
  assert.equal(options.minHandshakeSuccessRatio, 0.98);
  assert.equal(options.maxRateLimitFailureRatio, 0.02);
  assert.equal(options.maxCohortFailureRatio, 0.03);
  assert.equal(options.json, true);
});

test('parseReconnectChaosArgs rejects empty gateways, bad cohort/reason, and unknown args', () => {
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--gateways', '']), /at least one gateway/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--cohort', 'office']), /--cohort must be one of/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--reason', 'meteor']), /--reason must be one of/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--oops', 'x']), /Unknown argument/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--clients', '0']), /--clients must be a positive integer/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--inject-after-ms', '-1']), /--inject-after-ms must be a non-negative integer/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--max-recovery-slo-ms', '0']), /--max-recovery-slo-ms must be a positive integer/);
  assert.throws(() => parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--max-cohort-failure-ratio', '1.5']), /--max-cohort-failure-ratio must be a number between 0 and 1/);
});

test('buildGatewayFaultPlan builds sequential restart steps without restore for rolling restart', () => {
  const options = parseReconnectChaosArgs(['--fault', 'gateway-rolling-restart']);
  const plan = buildGatewayFaultPlan(options);

  assert.deepEqual(plan, [
    { action: 'restart', service: 'chat-websocket-app-1', restoreNeeded: false },
    { action: 'restart', service: 'chat-websocket-app-2', restoreNeeded: false },
  ]);
});

test('buildGatewayFaultPlan builds kill steps with restore for hard kill', () => {
  const options = parseReconnectChaosArgs(['--fault', 'gateway-hard-kill']);
  const plan = buildGatewayFaultPlan(options);

  assert.deepEqual(plan, [
    { action: 'kill', service: 'chat-websocket-app-1', restoreNeeded: true },
  ]);
});

test('buildGatewayFaultPlan omits restore when --no-restore is set', () => {
  const options = parseReconnectChaosArgs(['--fault', 'gateway-hard-kill', '--no-restore']);
  const plan = buildGatewayFaultPlan(options);

  assert.deepEqual(plan, [
    { action: 'kill', service: 'chat-websocket-app-1', restoreNeeded: false },
  ]);
});

test('buildReconnectLoadArgs always forwards the chaos defaults', () => {
  const options = parseReconnectChaosArgs(['--fault', 'gateway-rolling-restart']);

  assert.deepEqual(buildReconnectLoadArgs(options), [
    '--scenario', 'gateway-rolling-restart',
    '--reason', 'gateway_restart',
    '--clients', '5',
    '--reconnects-per-client', '6',
    '--cohort', 'synthetic',
    '--attempt-spacing-ms', '1000',
    '--jitter-ms', '250',
  ]);
});

test('buildReconnectLoadArgs appends only the gate ratios that were provided', () => {
  const options = parseReconnectChaosArgs([
    '--fault', 'gateway-hard-kill',
    '--min-ticket-issue-success-ratio', '0.99',
    '--max-cohort-failure-ratio', '0.03',
  ]);

  assert.deepEqual(buildReconnectLoadArgs(options), [
    '--scenario', 'gateway-hard-kill',
    '--reason', 'gateway_kill',
    '--clients', '5',
    '--reconnects-per-client', '6',
    '--cohort', 'synthetic',
    '--attempt-spacing-ms', '1000',
    '--jitter-ms', '250',
    '--min-ticket-issue-success-ratio', '0.99',
    '--max-cohort-failure-ratio', '0.03',
  ]);
});

test('summarizeReconnectChaos is non-blocking when the storm gate passes', () => {
  const summary = summarizeReconnectChaos({
    faultMode: 'gateway-rolling-restart',
    injectedContainers: ['chat-websocket-app-1', 'chat-websocket-app-2'],
    injectionOffsetMs: 120,
    recoveryElapsedMs: 8200,
    maxRecoverySloMs: 30000,
    dryRun: false,
    reconnectSummary: { ok: true, scenario: 'gateway-rolling-restart', failedGates: [] },
  });

  assert.equal(summary.faultMode, 'gateway-rolling-restart');
  assert.equal(summary.dryRun, false);
  assert.deepEqual(summary.injectedContainers, ['chat-websocket-app-1', 'chat-websocket-app-2']);
  assert.equal(summary.injectionOffsetMs, 120);
  assert.equal(summary.recoveryElapsedMs, 8200);
  assert.equal(summary.maxRecoverySloMs, 30000);
  assert.equal(summary.recoverySloMet, true);
  assert.equal(summary.reconnect.ok, true);
  assert.deepEqual(summary.failedGates, []);
  assert.equal(summary.releaseBlocking, false);
});

test('summarizeReconnectChaos is release blocking when the storm gate fails', () => {
  const summary = summarizeReconnectChaos({
    faultMode: 'gateway-hard-kill',
    injectedContainers: ['chat-websocket-app-1'],
    injectionOffsetMs: 80,
    recoveryElapsedMs: 6100,
    maxRecoverySloMs: 30000,
    dryRun: false,
    reconnectSummary: {
      ok: false,
      scenario: 'gateway-hard-kill',
      failedGates: ['handshake_success_ratio'],
    },
  });

  assert.equal(summary.releaseBlocking, true);
  assert.deepEqual(summary.failedGates, ['handshake_success_ratio']);
  assert.equal(summary.recoverySloMet, true);
});

test('summarizeReconnectChaos is release blocking when recovery SLO is exceeded', () => {
  const summary = summarizeReconnectChaos({
    faultMode: 'gateway-rolling-restart',
    injectedContainers: ['chat-websocket-app-1', 'chat-websocket-app-2'],
    injectionOffsetMs: 90,
    recoveryElapsedMs: 32000,
    maxRecoverySloMs: 30000,
    dryRun: false,
    reconnectSummary: { ok: true, scenario: 'gateway-rolling-restart', failedGates: [] },
  });

  assert.equal(summary.recoverySloMet, false);
  assert.equal(summary.releaseBlocking, true);
  assert.deepEqual(summary.failedGates, ['recovery_slo_ms']);
});

test('summarizeReconnectChaos dry-run is never blocking and carries no reconnect summary', () => {
  const summary = summarizeReconnectChaos({
    faultMode: 'gateway-rolling-restart',
    injectedContainers: ['chat-websocket-app-1', 'chat-websocket-app-2'],
    injectionOffsetMs: null,
    recoveryElapsedMs: null,
    maxRecoverySloMs: 30000,
    dryRun: true,
    reconnectSummary: null,
  });

  assert.equal(summary.dryRun, true);
  assert.equal(summary.reconnect, null);
  assert.equal(summary.injectionOffsetMs, null);
  assert.equal(summary.recoveryElapsedMs, null);
  assert.equal(summary.recoverySloMet, true);
  assert.deepEqual(summary.failedGates, []);
  assert.equal(summary.releaseBlocking, false);
});

test('exitCodeForReconnectChaosSummary returns 0 for non-blocking and 1 for blocking', () => {
  assert.equal(exitCodeForReconnectChaosSummary({ dryRun: false, releaseBlocking: false }), 0);
  assert.equal(exitCodeForReconnectChaosSummary({ dryRun: false, releaseBlocking: true }), 1);
  assert.equal(exitCodeForReconnectChaosSummary({ dryRun: true, releaseBlocking: false }), 0);
});
