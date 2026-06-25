export const prometheusQueries = {
  fanoutP95Seconds:
    'histogram_quantile(0.95, sum(rate(chat_redis_stream_worker_batch_latency_seconds_bucket{worker_role="fanout",outcome="success"}[1m])) by (le))',
  observedStreamShardCount:
    'count(count by (stream_shard) (chat_redis_stream_group_lag{consumer_group="fanout",stream_shard!="unknown"}))',
  maxStreamGroupLagEntries:
    'max(chat_redis_stream_group_lag{stream_shard!="unknown"})',
};

export function parsePhase8HotRoomGateArgs(argv, env = process.env) {
  const options = {
    room: 'hot',
    viewers: 10000,
    messagesPerSec: 10000,
    durationSeconds: 60,
    minReceivedRatio: 0.9,
    minStreamShardCount: 16,
    maxFanoutP95Ms: 500,
    maxStreamGroupLagEntries: 1000,
    prometheusUrl: env.CHAT_PROMETHEUS_URL ?? 'http://localhost:9090',
    loadScript: 'scripts/load-chat.mjs',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--room') {
      options.room = value;
    } else if (arg === '--viewers') {
      options.viewers = positiveInteger(value, arg);
    } else if (arg === '--messages-per-sec') {
      options.messagesPerSec = positiveInteger(value, arg);
    } else if (arg === '--duration') {
      options.durationSeconds = positiveInteger(value, arg);
    } else if (arg === '--min-received-ratio') {
      options.minReceivedRatio = ratio(value, arg);
    } else if (arg === '--min-stream-shards') {
      options.minStreamShardCount = positiveInteger(value, arg);
    } else if (arg === '--max-fanout-p95-ms') {
      options.maxFanoutP95Ms = positiveInteger(value, arg);
    } else if (arg === '--max-stream-lag') {
      options.maxStreamGroupLagEntries = positiveInteger(value, arg);
    } else if (arg === '--prometheus-url') {
      options.prometheusUrl = value;
    } else if (arg === '--load-script') {
      options.loadScript = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return options;
}

export function buildLoadChatArgs(options) {
  return [
    options.loadScript,
    '--room', options.room,
    '--viewers', String(options.viewers),
    '--messages-per-sec', String(options.messagesPerSec),
    '--duration', String(options.durationSeconds),
    '--min-received-ratio', String(options.minReceivedRatio),
    '--assert-room-seq-order',
  ];
}

export function assertLoadSummary(summary, options) {
  if (summary?.ok !== true) {
    throw new Error('load summary did not report ok=true');
  }
  const expectedSent = options.messagesPerSec * options.durationSeconds;
  if (summary.sent < expectedSent) {
    throw new Error(`load summary sent ${summary.sent}; expected at least ${expectedSent}`);
  }
  if (summary.viewers !== options.viewers) {
    throw new Error(`load summary viewers ${summary.viewers}; expected ${options.viewers}`);
  }
  if (summary.assertedRoomSeqOrder !== true) {
    throw new Error('load summary did not assert roomSeq order');
  }
  const minimumReceived = Math.ceil(summary.sent * options.minReceivedRatio);
  for (const [index, received] of (summary.receivedPerViewer ?? []).entries()) {
    if (received < minimumReceived) {
      throw new Error(`viewer ${index} received ${received}; minimum received is ${minimumReceived}`);
    }
  }
}

export function assertPrometheusSnapshot(snapshot, options) {
  const maxFanoutP95Seconds = options.maxFanoutP95Ms / 1000;
  if (snapshot.fanoutP95Seconds > maxFanoutP95Seconds) {
    throw new Error(`fanout p95 ${snapshot.fanoutP95Seconds}s exceeded ${maxFanoutP95Seconds}s`);
  }
  if (snapshot.observedStreamShardCount < options.minStreamShardCount) {
    throw new Error(
      `observed stream shard count ${snapshot.observedStreamShardCount}; expected at least ${options.minStreamShardCount}`,
    );
  }
  if (snapshot.maxStreamGroupLagEntries > options.maxStreamGroupLagEntries) {
    throw new Error(
      `stream group lag ${snapshot.maxStreamGroupLagEntries}; expected at most ${options.maxStreamGroupLagEntries}`,
    );
  }
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function ratio(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    throw new Error(`${name} must be a number between 0 and 1`);
  }
  return parsed;
}
