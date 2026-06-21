import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildRoleRoutingChecks,
  buildWebSocketHandshakeRequestOptions,
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

test('parseRoleRoutingCheckArgs rejects invalid timeout values', () => {
  assert.throws(
    () => parseRoleRoutingCheckArgs(['--timeout-ms', '0'], {}),
    /--timeout-ms must be a positive integer/,
  );
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

test('evaluateHttpCheckResponse rejects unexpected role body', () => {
  const check = buildRoleRoutingChecks({ baseUrl: 'http://localhost', adminToken: 'test', timeoutMs: 3000 })
    .find((candidate) => candidate.name === 'admin-health');

  const result = evaluateHttpCheckResponse(check, {
    status: 200,
    body: '{"status":"up"}',
    headers: { 'content-type': 'application/json' },
  });

  assert.equal(result.ok, false);
  assert.equal(result.reason, 'body_mismatch');
});

test('evaluateWebSocketHandshakeResponse accepts invalid ticket rejection from websocket role only', () => {
  const check = buildRoleRoutingChecks({ baseUrl: 'http://localhost', adminToken: 'test', timeoutMs: 3000 })
    .find((candidate) => candidate.name === 'websocket-invalid-ticket-handshake');

  assert.equal(evaluateWebSocketHandshakeResponse(check, { status: 401, body: '' }).ok, true);
  assert.equal(evaluateWebSocketHandshakeResponse(check, { status: 404, body: '' }).ok, false);
});

test('buildWebSocketHandshakeRequestOptions creates a raw upgrade request', () => {
  const check = buildRoleRoutingChecks({ baseUrl: 'http://localhost:8080', adminToken: 'test', timeoutMs: 3000 })
    .find((candidate) => candidate.name === 'websocket-invalid-ticket-handshake');

  const options = buildWebSocketHandshakeRequestOptions(check, 'phase7-key');

  assert.equal(options.hostname, 'localhost');
  assert.equal(options.port, '8080');
  assert.equal(options.path, '/api/ws/chat?ticket=phase7-invalid-routing-ticket');
  assert.equal(options.headers.Connection, 'Upgrade');
  assert.equal(options.headers.Upgrade, 'websocket');
  assert.equal(options.headers['Sec-WebSocket-Key'], 'phase7-key');
  assert.equal(options.headers['Sec-WebSocket-Version'], '13');
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
