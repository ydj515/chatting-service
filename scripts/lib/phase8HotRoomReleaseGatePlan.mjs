export const prometheusQueries = {
  fanoutP95Seconds:
    'histogram_quantile(0.95, sum(rate(chat_redis_stream_worker_batch_latency_seconds_bucket{worker_role="fanout",outcome="success"}[1m])) by (le))',
  observedStreamShardCount:
    'count(count by (stream_shard) (chat_redis_stream_group_lag{consumer_group="fanout",stream_shard!="unknown"}))',
  maxStreamGroupLagEntries:
    'max(chat_redis_stream_group_lag{stream_shard!="unknown"})',
};

const DEFAULT_STAGE_VIEWERS = [1000, 3000, 5000, 7000, 10000];

export function stageName(viewers) {
  return viewers % 1000 === 0 ? `${viewers / 1000}k` : String(viewers);
}

export function buildStageOptions(viewers, { messagesPerSec = viewers, durationSeconds = 60 } = {}) {
  return {
    name: stageName(viewers),
    viewers,
    messagesPerSec,
    durationSeconds,
  };
}

export function parsePhase8HotRoomGateArgs(argv, env = process.env) {
  const options = {
    mode: 'staged',
    room: 'hot',
    stages: DEFAULT_STAGE_VIEWERS.map((viewers) => buildStageOptions(viewers)),
    durationSeconds: 60,
    minReceivedRatio: 0.9,
    minStreamShardCount: 16,
    maxFanoutP95Ms: 500,
    maxStreamGroupLagEntries: 1000,
    prometheusUrl: env.CHAT_PROMETHEUS_URL ?? 'http://localhost:9090',
    loadScript: 'scripts/load-chat.mjs',
  };
  let singleStage = false;
  let explicitViewers = null;
  let explicitMessagesPerSec = null;
  let explicitStages = null;

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--single-stage') {
      singleStage = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--room') {
      options.room = value;
    } else if (arg === '--stages') {
      explicitStages = parseStageList(value, arg);
    } else if (arg === '--viewers') {
      explicitViewers = positiveInteger(value, arg);
    } else if (arg === '--messages-per-sec') {
      explicitMessagesPerSec = positiveInteger(value, arg);
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

  if (explicitStages && singleStage) {
    throw new Error('--stages cannot be used with --single-stage');
  }
  if (!singleStage && explicitViewers !== null) {
    throw new Error('--viewers requires --single-stage');
  }
  if (!singleStage && explicitMessagesPerSec !== null) {
    throw new Error('--messages-per-sec requires --single-stage');
  }

  if (singleStage) {
    const viewers = explicitViewers ?? 10000;
    options.mode = 'single-stage';
    options.stages = [
      buildStageOptions(viewers, {
        messagesPerSec: explicitMessagesPerSec ?? viewers,
        durationSeconds: options.durationSeconds,
      }),
    ];
  } else if (explicitStages) {
    options.stages = explicitStages.map((viewers) => buildStageOptions(viewers, {
      durationSeconds: options.durationSeconds,
    }));
  } else {
    options.stages = DEFAULT_STAGE_VIEWERS.map((viewers) => buildStageOptions(viewers, {
      durationSeconds: options.durationSeconds,
    }));
  }

  const compatibilityStage = options.stages[0];
  options.viewers = compatibilityStage.viewers;
  options.messagesPerSec = compatibilityStage.messagesPerSec;

  return options;
}

export function buildLoadChatArgs(options, stage = options.stages?.[0] ?? options) {
  return [
    options.loadScript,
    '--room', options.room,
    '--viewers', String(stage.viewers),
    '--messages-per-sec', String(stage.messagesPerSec),
    '--duration', String(stage.durationSeconds),
    '--min-received-ratio', String(options.minReceivedRatio),
    '--summary-mode', 'counts',
    '--label', stage.name,
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
  if (!Array.isArray(summary.receivedPerViewer)) {
    throw new Error('load summary receivedPerViewer must be an array');
  }
  if (summary.receivedPerViewer.length !== options.viewers) {
    throw new Error(
      `load summary receivedPerViewer length ${summary.receivedPerViewer.length}; expected ${options.viewers}`,
    );
  }
  const minimumReceived = Math.ceil(summary.sent * options.minReceivedRatio);
  for (const [index, received] of summary.receivedPerViewer.entries()) {
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

export function buildStageSuccess(stage, loadSummary, prometheusSnapshot) {
  return {
    name: stage.name,
    ok: true,
    options: {
      viewers: stage.viewers,
      messagesPerSec: stage.messagesPerSec,
      durationSeconds: stage.durationSeconds,
    },
    loadSummary,
    prometheusSnapshot,
  };
}

export function buildStageFailure(stage, error) {
  return {
    name: stage.name,
    ok: false,
    options: {
      viewers: stage.viewers,
      messagesPerSec: stage.messagesPerSec,
      durationSeconds: stage.durationSeconds,
    },
    error: {
      stage: stage.name,
      message: error.message,
      ...(error.stderr ? { stderr: error.stderr } : {}),
    },
  };
}

export function gateThresholds(options) {
  return {
    minStreamShardCount: options.minStreamShardCount,
    maxFanoutP95Ms: options.maxFanoutP95Ms,
    maxStreamGroupLagEntries: options.maxStreamGroupLagEntries,
  };
}

export function buildGateResult(options, stageResults) {
  const failed = stageResults.find((stage) => stage.ok === false) ?? null;
  const passed = stageResults.filter((stage) => stage.ok === true);
  return {
    ok: failed === null,
    mode: options.mode,
    lastPassedStage: passed.length > 0 ? passed[passed.length - 1].name : null,
    failedStage: failed?.name ?? null,
    stages: stageResults,
    thresholds: gateThresholds(options),
  };
}

export function buildFailedGateResult(options, stage, error, previousStageResults) {
  return buildGateResult(options, [
    ...previousStageResults,
    buildStageFailure(stage, error),
  ]);
}

export function prometheusQueryNumber(body, query) {
  const result = body.data?.result?.[0]?.value?.[1];
  if (result === undefined) {
    throw new Error(`Prometheus query returned no samples: ${query}`);
  }
  const value = Number(result);
  if (!Number.isFinite(value)) {
    throw new Error(`Prometheus query returned non-numeric value: ${query}`);
  }
  return value;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function parseStageList(value, name) {
  const parsed = value.split(',').map((entry) => positiveInteger(entry.trim(), name));
  if (parsed.length === 0) {
    throw new Error(`${name} must include at least one stage`);
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
