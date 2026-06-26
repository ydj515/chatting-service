import test from 'node:test';
import assert from 'node:assert/strict';
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildLoadChatArgs,
  prometheusQueryNumber,
  parsePhase8HotRoomGateArgs,
  prometheusQueries,
} from './phase8HotRoomReleaseGatePlan.mjs';

test('parsePhase8HotRoomGateArgs defaults to 10k messages per second for 60 seconds', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.equal(options.room, 'hot');
  assert.equal(options.viewers, 10000);
  assert.equal(options.messagesPerSec, 10000);
  assert.equal(options.durationSeconds, 60);
  assert.equal(options.minReceivedRatio, 0.9);
  assert.equal(options.minStreamShardCount, 16);
  assert.equal(options.maxFanoutP95Ms, 500);
  assert.equal(options.maxStreamGroupLagEntries, 1000);
});

test('buildLoadChatArgs includes release gate load arguments', () => {
  const args = buildLoadChatArgs(parsePhase8HotRoomGateArgs(['--room', 'arena']));

  assert.deepEqual(args, [
    'scripts/load-chat.mjs',
    '--room', 'arena',
    '--viewers', '10000',
    '--messages-per-sec', '10000',
    '--duration', '60',
    '--min-received-ratio', '0.9',
  ]);
});

test('assertLoadSummary accepts a successful load summary', () => {
  const options = parsePhase8HotRoomGateArgs(['--viewers', '2']);

  assertLoadSummary({
    ok: true,
    sent: 600000,
    viewers: 2,
    receivedPerViewer: [600000, 590000],
    minReceivedRatio: 0.9,
  }, options);
});

test('assertLoadSummary rejects insufficient delivery', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.throws(() => assertLoadSummary({
    ok: true,
    sent: 600000,
    viewers: 1,
    receivedPerViewer: [500000],
    minReceivedRatio: 0.9,
  }, { ...options, viewers: 1 }), /minimum received/);
});

test('assertLoadSummary rejects missing receivedPerViewer entries', () => {
  const options = parsePhase8HotRoomGateArgs(['--viewers', '2']);

  assert.throws(() => assertLoadSummary({
    ok: true,
    sent: 600000,
    viewers: 2,
    receivedPerViewer: [600000],
    minReceivedRatio: 0.9,
  }, options), /receivedPerViewer length/);
});

test('assertPrometheusSnapshot accepts fanout latency and shard distribution within thresholds', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assertPrometheusSnapshot({
    fanoutP95Seconds: 0.42,
    observedStreamShardCount: 16,
    maxStreamGroupLagEntries: 1000,
  }, options);
});

test('assertPrometheusSnapshot rejects too few observed stream shards', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.throws(() => assertPrometheusSnapshot({
    fanoutP95Seconds: 0.42,
    observedStreamShardCount: 1,
    maxStreamGroupLagEntries: 1000,
  }, options), /stream shard/);
});

test('prometheusQueries use bounded stream shard and fanout metrics', () => {
  assert.match(prometheusQueries.fanoutP95Seconds, /chat_redis_stream_worker_batch_latency_seconds_bucket/);
  assert.match(prometheusQueries.observedStreamShardCount, /stream_shard/);
  assert.match(prometheusQueries.maxStreamGroupLagEntries, /chat_redis_stream_group_lag/);
});

test('prometheusQueryNumber rejects empty query results', () => {
  assert.throws(() => prometheusQueryNumber({
    status: 'success',
    data: {
      result: [],
    },
  }, 'query'), /returned no samples/);
});
