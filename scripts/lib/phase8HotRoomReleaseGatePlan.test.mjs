import test from 'node:test';
import assert from 'node:assert/strict';
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildFailedGateResult,
  buildGateResult,
  buildLoadChatArgs,
  buildStageFailure,
  buildStageOptions,
  buildStageSuccess,
  prometheusQueryNumber,
  parsePhase8HotRoomGateArgs,
  prometheusQueries,
  stageName,
} from './phase8HotRoomReleaseGatePlan.mjs';

test('parsePhase8HotRoomGateArgs defaults to staged 1k 3k 5k 7k 10k gate', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.equal(options.mode, 'staged');
  assert.equal(options.room, 'hot');
  assert.deepEqual(options.stages, [
    { name: '1k', viewers: 1000, messagesPerSec: 1000, durationSeconds: 60 },
    { name: '3k', viewers: 3000, messagesPerSec: 3000, durationSeconds: 60 },
    { name: '5k', viewers: 5000, messagesPerSec: 5000, durationSeconds: 60 },
    { name: '7k', viewers: 7000, messagesPerSec: 7000, durationSeconds: 60 },
    { name: '10k', viewers: 10000, messagesPerSec: 10000, durationSeconds: 60 },
  ]);
  assert.equal(options.minReceivedRatio, 0.9);
  assert.equal(options.minAcceptedRatio, 0.99);
  assert.equal(options.senderCount, 16);
  assert.equal(options.minStreamShardCount, 16);
  assert.equal(options.maxFanoutP95Ms, 500);
  assert.equal(options.maxStreamGroupLagEntries, 1000);
});

test('parsePhase8HotRoomGateArgs accepts custom staged viewer list', () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,3000,5000']);

  assert.equal(options.mode, 'staged');
  assert.deepEqual(options.stages.map((stage) => stage.name), ['1k', '3k', '5k']);
  assert.deepEqual(options.stages.map((stage) => stage.viewers), [1000, 3000, 5000]);
  assert.deepEqual(options.stages.map((stage) => stage.messagesPerSec), [1000, 3000, 5000]);
});

test('parsePhase8HotRoomGateArgs keeps explicit single-stage compatibility mode', () => {
  const options = parsePhase8HotRoomGateArgs([
    '--single-stage',
    '--viewers', '7000',
    '--messages-per-sec', '6500',
    '--duration', '30',
  ]);

  assert.equal(options.mode, 'single-stage');
  assert.deepEqual(options.stages, [
    { name: '7k', viewers: 7000, messagesPerSec: 6500, durationSeconds: 30 },
  ]);
});

test('parsePhase8HotRoomGateArgs rejects ambiguous staged and single-stage options', () => {
  assert.throws(
    () => parsePhase8HotRoomGateArgs(['--stages', '1000,5000', '--messages-per-sec', '2000']),
    /--messages-per-sec requires --single-stage/,
  );
  assert.throws(
    () => parsePhase8HotRoomGateArgs(['--stages', '1000,5000', '--viewers', '1000']),
    /--viewers requires --single-stage/,
  );
});

test('stageName formats thousand stages with k suffix and raw integers otherwise', () => {
  assert.equal(stageName(1000), '1k');
  assert.equal(stageName(5000), '5k');
  assert.equal(stageName(750), '750');
});

test('buildLoadChatArgs includes staged release gate load arguments', () => {
  const options = parsePhase8HotRoomGateArgs(['--room', 'arena']);
  const args = buildLoadChatArgs(options, options.stages[0]);

  assert.deepEqual(args, [
    'scripts/load-chat.mjs',
    '--room', 'arena',
    '--viewers', '1000',
    '--messages-per-sec', '1000',
    '--duration', '60',
    '--min-received-ratio', '0.9',
    '--min-accepted-ratio', '0.99',
    '--senders', '16',
    '--summary-mode', 'counts',
    '--label', '1k',
    '--seed-room-shards', '16',
  ]);
});

test('assertLoadSummary accepts a successful load summary', () => {
  const options = parsePhase8HotRoomGateArgs(['--single-stage', '--viewers', '2']);

  assertLoadSummary({
    ok: true,
    sent: 600000,
    sender: {
      accepted: 600000,
    },
    viewers: 2,
    receivedPerViewer: [600000, 590000],
    minReceivedRatio: 0.9,
  }, options.stages[0], options);
});

test('assertLoadSummary rejects insufficient delivery', () => {
  const options = parsePhase8HotRoomGateArgs(['--single-stage', '--viewers', '1']);

  assert.throws(() => assertLoadSummary({
    ok: true,
    sent: 600000,
    sender: {
      accepted: 600000,
    },
    viewers: 1,
    receivedPerViewer: [500000],
    minReceivedRatio: 0.9,
  }, options.stages[0], options), /minimum received/);
});

test('assertLoadSummary rejects insufficient sender acceptance', () => {
  const options = parsePhase8HotRoomGateArgs([
    '--single-stage',
    '--viewers', '1',
    '--messages-per-sec', '1000',
    '--duration', '60',
  ]);

  assert.throws(() => assertLoadSummary({
    ok: true,
    sent: 60000,
    sender: {
      accepted: 59000,
    },
    viewers: 1,
    receivedPerViewer: [59000],
    minReceivedRatio: 0.9,
  }, options.stages[0], options), /accepted/);
});

test('assertLoadSummary rejects missing receivedPerViewer entries', () => {
  const options = parsePhase8HotRoomGateArgs(['--single-stage', '--viewers', '2']);

  assert.throws(() => assertLoadSummary({
    ok: true,
    sent: 600000,
    sender: {
      accepted: 600000,
    },
    viewers: 2,
    receivedPerViewer: [600000],
    minReceivedRatio: 0.9,
  }, options.stages[0], options), /receivedPerViewer length/);
});

test('buildGateResult reports last passed and failed stage names', () => {
  const options = parsePhase8HotRoomGateArgs([]);
  const result = buildGateResult(options, [
    buildStageSuccess(options.stages[0], { ok: true }, { fanoutP95Seconds: 0.1 }),
    buildStageSuccess(options.stages[1], { ok: true }, { fanoutP95Seconds: 0.2 }),
    buildStageFailure(options.stages[2], new Error('load runner failed')),
  ]);

  assert.equal(result.ok, false);
  assert.equal(result.mode, 'staged');
  assert.equal(result.lastPassedStage, '3k');
  assert.equal(result.failedStage, '5k');
  assert.equal(result.stages.length, 3);
  assert.equal(result.stages[2].error.message, 'load runner failed');
});

test('buildGateResult reports all stages passed', () => {
  const options = parsePhase8HotRoomGateArgs(['--stages', '1000,5000']);
  const result = buildGateResult(options, [
    buildStageSuccess(options.stages[0], { ok: true }, { fanoutP95Seconds: 0.1 }),
    buildStageSuccess(options.stages[1], { ok: true }, { fanoutP95Seconds: 0.2 }),
  ]);

  assert.equal(result.ok, true);
  assert.equal(result.lastPassedStage, '5k');
  assert.equal(result.failedStage, null);
  assert.equal(result.thresholds.maxFanoutP95Ms, 500);
});

test('buildFailedGateResult creates JSON-safe error objects with stderr', () => {
  const options = parsePhase8HotRoomGateArgs([]);
  const error = new Error('load runner failed');
  error.stderr = 'viewer 5104 issue-ticket POST http://localhost/api/ws-tickets failed: fetch failed';

  const result = buildFailedGateResult(options, options.stages[0], error, []);

  assert.equal(result.ok, false);
  assert.equal(result.failedStage, '1k');
  assert.match(result.stages[0].error.stderr, /viewer 5104/);
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
