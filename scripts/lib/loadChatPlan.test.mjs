import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';
import {
  assertMinimumAcceptedMessages,
  assertMinimumReceived,
  assertMinimumReceivedCounts,
  assertRoomSeqOrder,
  buildLoadUsername,
  createViewerMessageCollector,
  createSenderAckTracker,
  flattenChatMessages,
  formatLoadStepError,
  parseLoadChatArgs,
  readJsonResponse,
  redactSensitiveUrl,
  buildRoomShardSeedCommand,
  countChatMessagesInRawFrame,
  senderIndexForAttempt,
  acceptedClientMessageIdFromRawFrame,
  summarizeTakeoverDelivery,
  writeSocketBuffer,
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
    seedRoomShards: null,
    minAcceptedRatio: 0,
    senders: 1,
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

test('parseLoadChatArgs supports hot room shard seeding', () => {
  const options = parseLoadChatArgs(['--seed-room-shards', '16']);

  assert.equal(options.seedRoomShards, 16);
});

test('parseLoadChatArgs supports minimum sender acceptance ratio', () => {
  const options = parseLoadChatArgs(['--min-accepted-ratio', '0.99']);

  assert.equal(options.minAcceptedRatio, 0.99);
});

test('parseLoadChatArgs supports multiple sender sessions', () => {
  const options = parseLoadChatArgs(['--senders', '16']);

  assert.equal(options.senders, 16);
});

test('parseLoadChatArgs defaults to message retention for compatibility', () => {
  const options = parseLoadChatArgs([]);

  assert.equal(options.summaryMode, 'messages');
  assert.equal(options.label, null);
  assert.equal(options.seedRoomShards, null);
  assert.equal(options.minAcceptedRatio, 0);
  assert.equal(options.senders, 1);
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

test('assertMinimumAcceptedMessages rejects weak sender acceptance', () => {
  assert.throws(
    () => assertMinimumAcceptedMessages({ accepted: 98 }, 100, 0.99),
    /sender accepted only 98\/100/,
  );
});

test('createSenderAckTracker records attempts flushes and accepted ACK frames', () => {
  const tracker = createSenderAckTracker();

  tracker.recordAttempt('client-1');
  tracker.recordFlush();
  tracker.recordFrame({ type: 'CHAT_MESSAGE', clientMessageId: 'client-1' });
  tracker.recordFrame({ type: 'MESSAGE_ACCEPTED', clientMessageId: 'client-1' });
  tracker.recordAccepted('client-1');
  tracker.recordFrame({ type: 'MESSAGE_ACCEPTED', clientMessageId: 'unknown-client' });

  assert.deepEqual(tracker.snapshot(), {
    attempted: 1,
    flushed: 1,
    accepted: 1,
    pendingAcks: 0,
    unknownAccepted: 1,
    duplicateAccepted: 1,
    acceptanceRatio: 1,
  });
});

test('acceptedClientMessageIdFromRawFrame parses only MESSAGE_ACCEPTED frames', () => {
  assert.equal(
    acceptedClientMessageIdFromRawFrame('{"type":"MESSAGE_ACCEPTED","clientMessageId":"client-1"}'),
    'client-1',
  );
  assert.equal(
    acceptedClientMessageIdFromRawFrame('{"type":"CHAT_MESSAGE","clientMessageId":"client-1"}'),
    null,
  );
});

test('countChatMessagesInRawFrame counts single and batch messages without JSON parsing', () => {
  assert.equal(countChatMessagesInRawFrame('{"type":"CHAT_MESSAGE","chatRoomId":1}'), 1);
  assert.equal(
    countChatMessagesInRawFrame(
      '{"type":"CHAT_MESSAGE_BATCH","messages":[{"type":"CHAT_MESSAGE"},{"type":"CHAT_MESSAGE"}]}',
    ),
    2,
  );
  assert.equal(countChatMessagesInRawFrame('{"type":"MESSAGE_ACCEPTED"}'), 0);
});

test('senderIndexForAttempt distributes messages round-robin across senders', () => {
  assert.deepEqual(
    [1, 2, 3, 4, 5, 6].map((attempt) => senderIndexForAttempt(attempt, 3)),
    [0, 1, 2, 0, 1, 2],
  );
});

test('writeSocketBuffer waits for drain when socket reports backpressure', async () => {
  const socket = new EventEmitter();
  let drained = false;
  socket.destroyed = false;
  socket.write = () => false;

  const write = writeSocketBuffer(socket, Buffer.from('payload')).then(() => {
    drained = true;
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(drained, false);

  socket.emit('drain');
  await write;
  assert.equal(drained, true);
});

test('writeSocketBuffer returns without a promise when socket flushes immediately', () => {
  const socket = new EventEmitter();
  socket.destroyed = false;
  socket.write = () => true;

  assert.equal(writeSocketBuffer(socket, Buffer.from('payload')), undefined);
});

test('createViewerMessageCollector can count without retaining every message', () => {
  const collector = createViewerMessageCollector({ retainMessages: false, sampleLimit: 2 });

  collector.addViewer(1);
  collector.record(1, [{ roomSeq: 1 }, { roomSeq: 2 }, { roomSeq: 3 }]);
  collector.recordCount(1, 2);

  assert.deepEqual(collector.receivedCounts(), [5]);
  assert.deepEqual(collector.receivedSamples(), [null]);
  assert.deepEqual(collector.sampleSummary(), [
    {
      userId: 1,
      received: 5,
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

test('redactSensitiveUrl removes ticket and token query values', () => {
  assert.equal(
    redactSensitiveUrl('ws://localhost/api/ws/chat?ticket=secret&token=session&room=1'),
    'ws://localhost/api/ws/chat?ticket=%5Bredacted%5D&token=%5Bredacted%5D&room=1',
  );
});

test('formatLoadStepError includes label viewer step method and redacted URL', () => {
  const error = formatLoadStepError({
    label: '5k',
    viewerIndex: 5104,
    step: 'issue-ticket',
    method: 'POST',
    url: 'http://localhost/api/ws-tickets?token=secret',
    cause: new Error('fetch failed'),
  });

  assert.match(error, /^\[5k\] viewer 5104 issue-ticket POST http:\/\/localhost\/api\/ws-tickets/);
  assert.match(error, /failed: fetch failed$/);
  assert.doesNotMatch(error, /secret/);
});

test('formatLoadStepError formats sender steps without viewer index', () => {
  const error = formatLoadStepError({
    label: '1k',
    step: 'create-room',
    method: 'POST',
    url: 'http://localhost/api/chat-rooms',
    cause: new Error('HTTP 502 bad gateway'),
  });

  assert.equal(error, '[1k] create-room POST http://localhost/api/chat-rooms failed: HTTP 502 bad gateway');
});

test('buildRoomShardSeedCommand targets room_storage_configs through compose postgres', () => {
  const command = buildRoomShardSeedCommand({
    roomId: 42,
    shardCount: 16,
    env: {
      DB_USERNAME: 'chatuser',
      DB_NAME: 'chatdb',
    },
  });

  assert.equal(command.command, 'docker');
  assert.deepEqual(command.args.slice(0, 7), [
    'compose',
    'exec',
    '-T',
    'postgres',
    'psql',
    '-U',
    'chatuser',
  ]);
  assert.match(command.args.at(-1), /room_storage_configs/);
  assert.match(command.args.at(-1), /fanout_shard_count/);
  assert.doesNotMatch(command.args.at(-1), /:room_id|:shard_count/);
  assert.match(command.args.at(-1), /VALUES\s*\(\s*42,\s*'HOT',\s*16,\s*16,/);
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
