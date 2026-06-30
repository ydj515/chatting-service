import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  assertMinimumReceived,
  assertMinimumReceivedCounts,
  assertRoomSeqOrder,
  buildLoadUsername,
  createViewerMessageCollector,
  flattenChatMessages,
  parseLoadChatArgs,
  readJsonResponse,
  summarizeTakeoverDelivery,
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
    takeoverDeliverySummary: false,
    summaryMode: 'messages',
    label: null,
  });
});

test('parseLoadChatArgs enables takeover delivery summary without raw order assertion', () => {
  const options = parseLoadChatArgs(['--takeover-delivery-summary']);

  assert.equal(options.takeoverDeliverySummary, true);
  assert.equal(options.assertRoomSeqOrder, false);
});

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

test('assertRoomSeqOrder rejects a live feed inversion', () => {
  assert.throws(
    () => assertRoomSeqOrder([{ roomSeq: 10 }, { roomSeq: 9 }]),
    /roomSeq order violated/,
  );
});

test('summarizeTakeoverDelivery treats duplicate replay as diagnostic when client-visible output is stable', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { messageId: 'm2', roomSeq: 2 },
      { messageId: 'm3', roomSeq: 3 },
      { messageId: 'm2', roomSeq: 2 },
    ],
  ], { sent: 3, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, false);
  assert.equal(summary.aggregate.rawInversionCount, 1);
  assert.equal(summary.aggregate.duplicateReplayCount, 1);
  assert.equal(summary.aggregate.firstSeenLateDeliveryCount, 0);
  assert.equal(summary.viewers[0].clientVisible.uniqueCount, 3);
  assert.equal(summary.viewers[0].clientVisible.minReceivedSatisfied, true);
});

test('summarizeTakeoverDelivery marks first-seen late delivery as release blocking', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { messageId: 'm3', roomSeq: 3 },
      { messageId: 'm2', roomSeq: 2 },
    ],
  ], { sent: 3, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.aggregate.rawInversionCount, 1);
  assert.equal(summary.aggregate.duplicateReplayCount, 0);
  assert.equal(summary.aggregate.firstSeenLateDeliveryCount, 1);
  assert.equal(summary.viewers[0].releaseBlocking, true);
});

test('summarizeTakeoverDelivery marks unclassifiable payloads as release blocking', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { roomSeq: 2 },
      { messageId: 'm3' },
    ],
  ], { sent: 3, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.aggregate.missingMessageIdCount, 1);
  assert.equal(summary.aggregate.missingRoomSeqCount, 1);
});

test('summarizeTakeoverDelivery applies minimum receive ratio to unique client-visible messages', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { messageId: 'm1', roomSeq: 1 },
    ],
  ], { sent: 2, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.viewers[0].clientVisible.uniqueCount, 1);
  assert.equal(summary.viewers[0].clientVisible.minimumRequired, 2);
  assert.equal(summary.viewers[0].clientVisible.minReceivedSatisfied, false);
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

test('readJsonResponse reports HTTP status and raw body before parsing JSON', async () => {
  await assert.rejects(
    () => readJsonResponse(
      {
        ok: false,
        status: 502,
        text: async () => '<html>bad gateway</html>',
      },
      { method: 'POST', url: 'http://localhost/api/test' },
    ),
    /POST http:\/\/localhost\/api\/test failed: 502 <html>bad gateway<\/html>/,
  );
});
