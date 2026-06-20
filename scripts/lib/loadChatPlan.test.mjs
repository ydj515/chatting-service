import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  assertMinimumReceived,
  assertRoomSeqOrder,
  buildLoadUsername,
  flattenChatMessages,
  parseLoadChatArgs,
} from './loadChatPlan.mjs';

test('parseLoadChatArgs maps Phase 6 load-chat options', () => {
  const options = parseLoadChatArgs([
    '--room',
    'hot',
    '--viewers',
    '3',
    '--messages-per-sec',
    '100',
      '--duration',
      '30',
      '--metadata-file',
      '/tmp/phase6-load.json',
      '--drain-wait',
      '12',
      '--min-received-ratio',
      '0.9',
      '--assert-room-seq-order',
  ]);

  assert.deepEqual(options, {
    room: 'hot',
    viewers: 3,
    messagesPerSec: 100,
    durationSeconds: 30,
    metadataFile: '/tmp/phase6-load.json',
    drainWaitSeconds: 12,
    minReceivedRatio: 0.9,
    assertRoomSeqOrder: true,
  });
});

test('assertRoomSeqOrder rejects a live feed inversion', () => {
  assert.throws(
    () => assertRoomSeqOrder([{ roomSeq: 10 }, { roomSeq: 9 }]),
    /roomSeq order violated/,
  );
});

test('flattenChatMessages extracts single and batch chat payloads', () => {
  assert.deepEqual(flattenChatMessages({ type: 'CHAT_MESSAGE', roomSeq: 1 }), [
    { type: 'CHAT_MESSAGE', roomSeq: 1 },
  ]);
  assert.deepEqual(
    flattenChatMessages({
      type: 'CHAT_MESSAGE_BATCH',
      messages: [{ roomSeq: 1 }, { roomSeq: 2 }],
    }),
    [{ roomSeq: 1 }, { roomSeq: 2 }],
  );
});

test('buildLoadUsername stays within server validation limits', () => {
  const username = buildLoadUsername('load_viewer_10000', 'mqk9xr711bps');

  assert.match(username, /^[a-zA-Z0-9_]+$/);
  assert.ok(username.length >= 3);
  assert.ok(username.length <= 20);
});

test('assertMinimumReceived rejects weak fanout verification samples', () => {
  assert.throws(
    () => assertMinimumReceived([[{ roomSeq: 1 }]], 200, 0.9),
    /received only 1\/200/,
  );
});
