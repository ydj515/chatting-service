import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildLoadChatArgs,
  buildRoutingCheckArgs,
  buildTakeoverSmokeSummary,
  buildRunCapturedOptions,
  coerceRoutingCheckOutput,
  findOwnerContainer,
  parseDockerInspectRows,
  parseLoadChatJson,
  parseTakeoverSmokeArgs,
  parseWorkerContainerIds,
  redisOwnerScanPattern,
} from './phase6TakeoverSmokePlan.mjs';

test('parseTakeoverSmokeArgs keeps Phase 6 production lease defaults in the smoke window', () => {
  const options = parseTakeoverSmokeArgs([
    '--room',
    'phase6',
    '--viewers',
    '3',
    '--messages-per-sec',
    '20',
    '--duration',
    '20',
    '--kill-after',
    '5',
  ]);

  assert.equal(options.service, 'chat-worker-app-1');
  assert.equal(options.restoreScale, 2);
  assert.equal(options.room, 'phase6');
  assert.equal(options.viewers, 3);
  assert.equal(options.messagesPerSec, 20);
  assert.equal(options.durationSeconds, 20);
  assert.equal(options.killAfterSeconds, 5);
  assert.equal(options.drainWaitSeconds, 12);
  assert.equal(options.minReceivedRatio, 0.9);
  assert.equal(options.verifyRoutingAfterRestore, false);
  assert.equal(options.routingCheckBaseUrl, 'http://localhost');
  assert.equal(options.routingCheckAdminToken, 'test');
  // opt-in이 아니면 timeout은 검증하지 않고 raw 기본값을 그대로 둔다.
  assert.equal(options.routingCheckTimeoutMs, '3000');
});

test('parseTakeoverSmokeArgs ignores invalid routing timeout env when routing check is not opted in', () => {
  const previous = process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS;
  process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS = 'not-a-number';
  try {
    assert.doesNotThrow(() => parseTakeoverSmokeArgs([]));
  } finally {
    if (previous === undefined) {
      delete process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS;
    } else {
      process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS = previous;
    }
  }
});

test('parseTakeoverSmokeArgs validates routing timeout env when routing check is opted in', () => {
  const previous = process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS;
  process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS = 'not-a-number';
  try {
    assert.throws(
      () => parseTakeoverSmokeArgs(['--verify-routing-after-restore']),
      /CHAT_PHASE7_ROUTE_TIMEOUT_MS/,
    );
  } finally {
    if (previous === undefined) {
      delete process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS;
    } else {
      process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS = previous;
    }
  }
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
      '--timeout-ms',
      '1200',
      '--json',
    ],
  );
});

test('coerceRoutingCheckOutput keeps captured routing check JSON printable', () => {
  assert.equal(coerceRoutingCheckOutput('{"ok":true}\n'), '{"ok":true}\n');
  assert.equal(coerceRoutingCheckOutput(Buffer.from('{"ok":true}\n')), '{"ok":true}\n');
  assert.equal(coerceRoutingCheckOutput(null), '');
});

test('buildLoadChatArgs asks load-chat for takeover delivery summary and minimum fanout receipt', () => {
  assert.deepEqual(
    buildLoadChatArgs({
      room: 'phase6',
      viewers: 3,
      messagesPerSec: 20,
      durationSeconds: 20,
      drainWaitSeconds: 12,
      minReceivedRatio: 0.9,
    }),
    [
      '--room',
      'phase6',
      '--viewers',
      '3',
      '--messages-per-sec',
      '20',
      '--duration',
      '20',
      '--drain-wait',
      '12',
      '--min-received-ratio',
      '0.9',
      '--takeover-delivery-summary',
    ],
  );
});

test('buildTakeoverSmokeSummary carries takeover delivery and flips ok on release blocking summary', () => {
  const summary = buildTakeoverSmokeSummary({
    killedContainer: {
      name: 'chatting-service-chat-worker-app-1-1',
      id: 'abcdef1234567890',
    },
    loadSummary: {
      roomId: 7,
      sent: 10,
      receivedPerViewer: [10, 11],
      minReceivedRatio: 0.9,
      assertedRoomSeqOrder: false,
      takeoverDeliverySummary: true,
      takeoverDelivery: {
        releaseBlocking: true,
        aggregate: { firstSeenLateDeliveryCount: 1 },
        viewers: [],
      },
    },
  });

  assert.equal(summary.ok, false);
  assert.equal(summary.killedContainer, 'chatting-service-chat-worker-app-1-1');
  assert.equal(summary.killedContainerId, 'abcdef123456');
  assert.equal(summary.takeoverDelivery.releaseBlocking, true);
});

test('buildTakeoverSmokeSummary fails closed when takeover delivery summary is requested but missing', () => {
  const summary = buildTakeoverSmokeSummary({
    killedContainer: {
      name: 'chatting-service-chat-worker-app-1-1',
      id: 'abcdef1234567890',
    },
    loadSummary: {
      roomId: 7,
      sent: 10,
      receivedPerViewer: [10, 10],
      minReceivedRatio: 0.9,
      assertedRoomSeqOrder: false,
      takeoverDeliverySummary: true,
    },
  });

  assert.equal(summary.ok, false);
  assert.equal(summary.takeoverDeliverySummary, true);
  assert.equal(summary.takeoverDeliveryMissing, true);
});

test('parseWorkerContainerIds requires at least two worker replicas', () => {
  assert.deepEqual(parseWorkerContainerIds('abc\n\ndef\n'), ['abc', 'def']);
  assert.throws(() => parseWorkerContainerIds('abc\n'), /at least 2/);
});

test('redisOwnerScanPattern matches all fanout owner shards for the created room', () => {
  assert.equal(
    redisOwnerScanPattern({ roomId: 42, keyPrefix: 'chat:fanout:owner:room:' }),
    'chat:fanout:owner:room:42:shard:*',
  );
});

test('findOwnerContainer maps lease value hostname to a docker container id', () => {
  const rows = parseDockerInspectRows([
    'abc123|host-a|/chatting-service-chat-worker-app-1-1',
    'def456|host-b|/chatting-service-chat-worker-app-1-2',
  ].join('\n'));

  assert.deepEqual(rows, [
    { id: 'abc123', hostname: 'host-a', name: 'chatting-service-chat-worker-app-1-1' },
    { id: 'def456', hostname: 'host-b', name: 'chatting-service-chat-worker-app-1-2' },
  ]);
  assert.equal(findOwnerContainer('host-b:lease-token', rows)?.id, 'def456');
});

test('parseLoadChatJson extracts the final JSON summary from mixed stdout', () => {
  const summary = parseLoadChatJson([
    'load started',
    '{',
    '  "ok": true,',
    '  "roomId": 7,',
    '  "sent": 10,',
    '  "receivedPerViewer": [10, 10]',
    '}',
  ].join('\n'));

  assert.deepEqual(summary, {
    ok: true,
    roomId: 7,
    sent: 10,
    receivedPerViewer: [10, 10],
  });
});

test('parseLoadChatJson handles nested objects in the final JSON summary', () => {
  const summary = parseLoadChatJson([
    'load started with {non-json} marker',
    '{',
    '  "ok": true,',
    '  "roomId": 7,',
    '  "metadata": { "owner": "worker-1" }',
    '}',
  ].join('\n'));

  assert.deepEqual(summary, {
    ok: true,
    roomId: 7,
    metadata: { owner: 'worker-1' },
  });
});

test('buildRunCapturedOptions adds a timeout to blocking docker commands', () => {
  assert.deepEqual(
    buildRunCapturedOptions({ env: { A: 'B' }, timeoutMs: 1234 }),
    {
      env: { A: 'B' },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 1234,
    },
  );
});
