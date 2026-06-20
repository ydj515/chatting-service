import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildLoadChatArgs,
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
});

test('buildLoadChatArgs always verifies roomSeq order and minimum fanout receipt', () => {
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
      '--assert-room-seq-order',
    ],
  );
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
