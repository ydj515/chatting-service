import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { test } from 'node:test';
import {
  RECONNECT_COHORTS,
  RECONNECT_REASONS,
  buildReconnectAttemptPlan,
  buildWebSocketSocketOptions,
  classifyTicketIssueFailure,
  exitCodeForReconnectSummary,
  parseReconnectLoadArgs,
  summarizeReconnectAttempts,
  validateWebSocketHandshake,
} from './phase7ReconnectLoadPlan.mjs';

test('parseReconnectLoadArgs maps CLI values over defaults', () => {
  const options = parseReconnectLoadArgs([
    '--scenario',
    'gateway-rolling-restart',
    '--clients',
    '5',
    '--reconnects-per-client',
    '3',
    '--cohort',
    'nat_proxy',
    '--reason',
    'gateway_restart',
    '--attempt-spacing-ms',
    '250',
    '--jitter-ms',
    '50',
    '--min-ticket-issue-success-ratio',
    '0.99',
    '--min-handshake-success-ratio',
    '0.98',
    '--max-rate-limit-failure-ratio',
    '0.02',
    '--max-cohort-failure-ratio',
    '0.03',
  ], {
    CHAT_PHASE7_RECONNECT_TIMEOUT_MS: '4000',
  });

  assert.deepEqual(options, {
    scenario: 'gateway-rolling-restart',
    clients: 5,
    reconnectsPerClient: 3,
    cohort: 'nat_proxy',
    reason: 'gateway_restart',
    attemptSpacingMs: 250,
    jitterMs: 50,
    minTicketIssueSuccessRatio: 0.99,
    minHandshakeSuccessRatio: 0.98,
    maxRateLimitFailureRatio: 0.02,
    maxCohortFailureRatio: 0.03,
    timeoutMs: 4000,
    readyFile: null,
  });
});

test('parseReconnectLoadArgs parses --ready-file and defaults it to null', () => {
  const withReadyFile = parseReconnectLoadArgs(['--ready-file', '/tmp/storm-ready.json'], {});
  assert.equal(withReadyFile.readyFile, '/tmp/storm-ready.json');

  const withoutReadyFile = parseReconnectLoadArgs([], {});
  assert.equal(withoutReadyFile.readyFile, null);
});

test('RECONNECT_COHORTS and RECONNECT_REASONS expose the bounded enums', () => {
  assert.deepEqual(RECONNECT_COHORTS, ['direct', 'nat_proxy', 'mobile_carrier', 'synthetic']);
  assert.deepEqual(RECONNECT_REASONS, ['network_flap', 'gateway_restart', 'gateway_kill', 'deploy', 'unknown']);
});

test('parseReconnectLoadArgs rejects unknown cohort and invalid ratios', () => {
  assert.throws(
    () => parseReconnectLoadArgs(['--cohort', 'office'], {}),
    /--cohort must be one of/,
  );
  assert.throws(
    () => parseReconnectLoadArgs(['--min-ticket-issue-success-ratio', '1.5'], {}),
    /--min-ticket-issue-success-ratio must be a number between 0 and 1/,
  );
});

test('parseReconnectLoadArgs lets CLI timeout override an invalid env timeout', () => {
  const options = parseReconnectLoadArgs(['--timeout-ms', '1200'], {
    CHAT_PHASE7_RECONNECT_TIMEOUT_MS: 'not-a-number',
  });

  assert.equal(options.timeoutMs, 1200);
});

test('parseReconnectLoadArgs validates env timeout only when CLI does not override it', () => {
  assert.throws(
    () => parseReconnectLoadArgs([], { CHAT_PHASE7_RECONNECT_TIMEOUT_MS: 'not-a-number' }),
    /CHAT_PHASE7_RECONNECT_TIMEOUT_MS must be a positive integer/,
  );
});

test('buildReconnectAttemptPlan creates bounded per-client reconnect attempts', () => {
  const plan = buildReconnectAttemptPlan({
    clients: 2,
    reconnectsPerClient: 3,
    attemptSpacingMs: 100,
    jitterMs: 0,
  });

  assert.deepEqual(plan, [
    { clientIndex: 0, attemptIndex: 0, delayMs: 0 },
    { clientIndex: 0, attemptIndex: 1, delayMs: 100 },
    { clientIndex: 0, attemptIndex: 2, delayMs: 200 },
    { clientIndex: 1, attemptIndex: 0, delayMs: 0 },
    { clientIndex: 1, attemptIndex: 1, delayMs: 100 },
    { clientIndex: 1, attemptIndex: 2, delayMs: 200 },
  ]);
});

test('summarizeReconnectAttempts passes when ticket issue and handshake gates are satisfied', () => {
  const summary = summarizeReconnectAttempts([
    successAttempt('direct'),
    successAttempt('direct'),
    successAttempt('synthetic'),
  ], {
    scenario: 'baseline-reconnect',
    durationSeconds: 12,
    minTicketIssueSuccessRatio: 0.999,
    minHandshakeSuccessRatio: 0.999,
    maxRateLimitFailureRatio: 0.001,
    maxCohortFailureRatio: 0.003,
  });

  assert.equal(summary.ok, true);
  assert.equal(summary.normalReconnectAttempts, 3);
  assert.equal(summary.normalReconnectTicketIssued, 3);
  assert.equal(summary.normalReconnectHandshakeSucceeded, 3);
  assert.equal(summary.rates.ticketIssueSuccessRate, 1);
  assert.equal(summary.rates.handshakeSuccessRate, 1);
  assert.deepEqual(summary.failedGates, []);
});

test('summarizeReconnectAttempts fails release gate on normal reconnect rate limit failures', () => {
  const summary = summarizeReconnectAttempts([
    successAttempt('nat_proxy'),
    rateLimitedAttempt('nat_proxy', 'rate_limited_ip'),
  ], {
    scenario: 'nat-proxy-cohort',
    durationSeconds: 15,
    minTicketIssueSuccessRatio: 0.999,
    minHandshakeSuccessRatio: 0.999,
    maxRateLimitFailureRatio: 0.001,
    maxCohortFailureRatio: 0.003,
  });

  assert.equal(summary.ok, false);
  assert.equal(summary.normalReconnectAttempts, 2);
  assert.equal(summary.normalReconnectTicketIssued, 1);
  assert.equal(summary.normalReconnectRateLimited, 1);
  assert.equal(summary.rates.rateLimitFailureRate, 0.5);
  assert.equal(summary.cohorts.nat_proxy.failureRate, 0.5);
  assert.deepEqual(summary.failedGates, [
    'ticket_issue_success_ratio',
    'rate_limit_failure_ratio',
    'cohort_failure_ratio:nat_proxy',
  ]);
  assert.equal(exitCodeForReconnectSummary(summary), 1);
});

test('summarizeReconnectAttempts does not add handshake gate when no tickets were issued', () => {
  const summary = summarizeReconnectAttempts([
    rateLimitedAttempt('nat_proxy', 'rate_limited'),
    rateLimitedAttempt('nat_proxy', 'rate_limited'),
  ], {
    scenario: 'nat-proxy-cohort',
    durationSeconds: 15,
    minTicketIssueSuccessRatio: 0.999,
    minHandshakeSuccessRatio: 0.999,
    maxRateLimitFailureRatio: 0.001,
    maxCohortFailureRatio: 0.003,
  });

  assert.equal(summary.normalReconnectTicketIssued, 0);
  assert.equal(summary.rates.handshakeSuccessRate, null);
  assert.deepEqual(summary.failedGates, [
    'ticket_issue_success_ratio',
    'rate_limit_failure_ratio',
    'cohort_failure_ratio:nat_proxy',
  ]);
});

test('summarizeReconnectAttempts fails release gate on handshake failures after ticket issue', () => {
  const summary = summarizeReconnectAttempts([
    successAttempt('mobile_carrier'),
    handshakeFailedAttempt('mobile_carrier'),
  ], {
    scenario: 'mobile-carrier-flap',
    durationSeconds: 15,
    minTicketIssueSuccessRatio: 0.999,
    minHandshakeSuccessRatio: 0.999,
    maxRateLimitFailureRatio: 0.001,
    maxCohortFailureRatio: 0.003,
  });

  assert.equal(summary.ok, false);
  assert.equal(summary.normalReconnectTicketIssued, 2);
  assert.equal(summary.normalReconnectHandshakeSucceeded, 1);
  assert.equal(summary.normalReconnectHandshakeFailed, 1);
  assert.deepEqual(summary.failedGates, [
    'handshake_success_ratio',
    'cohort_failure_ratio:mobile_carrier',
  ]);
});

test('classifyTicketIssueFailure maps HTTP status and body into bounded outcomes', () => {
  assert.equal(classifyTicketIssueFailure(null), 'failure');
  assert.equal(classifyTicketIssueFailure(undefined), 'failure');
  assert.equal(
    classifyTicketIssueFailure({ status: 429, body: '{"message":"user rate limit exceeded"}' }),
    'rate_limited_user',
  );
  assert.equal(
    classifyTicketIssueFailure({ status: 429, body: '{"message":"ip rate limit exceeded"}' }),
    'rate_limited_ip',
  );
  assert.equal(
    classifyTicketIssueFailure({ status: 429, body: '' }),
    'rate_limited',
  );
  assert.equal(
    classifyTicketIssueFailure({ status: 401, body: '{"status":401}' }),
    'auth_failure',
  );
  assert.equal(
    classifyTicketIssueFailure({ status: 503, body: 'unavailable' }),
    'failure',
  );
});

test('buildWebSocketSocketOptions includes SNI servername for secure WebSocket URLs', () => {
  assert.deepEqual(buildWebSocketSocketOptions(new URL('wss://chat.example.com/ws')), {
    host: 'chat.example.com',
    port: 443,
    servername: 'chat.example.com',
  });
  assert.deepEqual(buildWebSocketSocketOptions(new URL('ws://localhost:18080/ws')), {
    host: 'localhost',
    port: 18080,
  });
});

test('validateWebSocketHandshake requires status, upgrade headers, and Sec-WebSocket-Accept', () => {
  const key = 'phase7-reconnect-key';
  const accept = crypto
    .createHash('sha1')
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest('base64');

  assert.equal(validateWebSocketHandshake([
    'HTTP/1.1 101 Switching Protocols',
    'Upgrade: websocket',
    'Connection: keep-alive, Upgrade',
    `Sec-WebSocket-Accept: ${accept}`,
  ].join('\r\n'), key).ok, true);

  assert.deepEqual(validateWebSocketHandshake([
    'HTTP/1.1 101 Switching Protocols',
    'Connection: Upgrade',
    `Sec-WebSocket-Accept: ${accept}`,
  ].join('\r\n'), key), {
    ok: false,
    reason: 'missing_upgrade_header',
    statusLine: 'HTTP/1.1 101 Switching Protocols',
  });

  assert.deepEqual(validateWebSocketHandshake([
    'HTTP/1.1 101 Switching Protocols',
    'Upgrade: websocket',
    'Connection: Upgrade',
    'Sec-WebSocket-Accept: wrong',
  ].join('\r\n'), key), {
    ok: false,
    reason: 'invalid_sec_websocket_accept',
    statusLine: 'HTTP/1.1 101 Switching Protocols',
  });
});

function successAttempt(cohort) {
  return {
    cohort,
    ticketIssued: true,
    rateLimited: false,
    handshakeSucceeded: true,
    handshakeFailed: false,
  };
}

function rateLimitedAttempt(cohort, outcome) {
  return {
    cohort,
    ticketIssued: false,
    rateLimited: true,
    failureOutcome: outcome,
    handshakeSucceeded: false,
    handshakeFailed: false,
  };
}

function handshakeFailedAttempt(cohort) {
  return {
    cohort,
    ticketIssued: true,
    rateLimited: false,
    handshakeSucceeded: false,
    handshakeFailed: true,
  };
}
