export function parseTakeoverSmokeArgs(argv) {
  const options = {
    service: 'chat-worker-app-1',
    restoreScale: 2,
    room: 'phase6-takeover',
    viewers: 3,
    messagesPerSec: 20,
    durationSeconds: 20,
    killAfterSeconds: 5,
    drainWaitSeconds: 12,
    minReceivedRatio: 0.9,
    ownerLeaseKeyPrefix: 'chat:fanout:owner:room:',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--service') {
      options.service = value;
    } else if (arg === '--restore-scale') {
      options.restoreScale = positiveInteger(value, arg);
    } else if (arg === '--room') {
      options.room = value;
    } else if (arg === '--viewers') {
      options.viewers = positiveInteger(value, arg);
    } else if (arg === '--messages-per-sec') {
      options.messagesPerSec = positiveInteger(value, arg);
    } else if (arg === '--duration') {
      options.durationSeconds = positiveInteger(value, arg);
    } else if (arg === '--kill-after') {
      options.killAfterSeconds = positiveInteger(value, arg);
    } else if (arg === '--drain-wait') {
      options.drainWaitSeconds = positiveInteger(value, arg);
    } else if (arg === '--min-received-ratio') {
      options.minReceivedRatio = ratio(value, arg);
    } else if (arg === '--owner-lease-key-prefix') {
      options.ownerLeaseKeyPrefix = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return options;
}

export function buildLoadChatArgs(options) {
  return [
    '--room',
    options.room,
    '--viewers',
    String(options.viewers),
    '--messages-per-sec',
    String(options.messagesPerSec),
    '--duration',
    String(options.durationSeconds),
    '--drain-wait',
    String(options.drainWaitSeconds),
    '--min-received-ratio',
    String(options.minReceivedRatio),
    '--assert-room-seq-order',
  ];
}

export function parseWorkerContainerIds(output) {
  const ids = output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  if (ids.length < 2) {
    throw new Error(`Phase 6 takeover smoke requires at least 2 worker replicas, got ${ids.length}`);
  }
  return ids;
}

export function redisOwnerScanPattern({ roomId, keyPrefix }) {
  return `${keyPrefix}${roomId}:shard:*`;
}

export function parseDockerInspectRows(output) {
  return output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [id, hostname, rawName] = line.split('|');
      return {
        id,
        hostname,
        name: rawName?.replace(/^\//, '') ?? '',
      };
    });
}

export function findOwnerContainer(leaseValue, containers) {
  const ownerHostname = String(leaseValue).split(':', 1)[0];
  return containers.find((container) => container.hostname === ownerHostname) ?? null;
}

export function parseLoadChatJson(stdout) {
  const start = stdout.lastIndexOf('{');
  const end = stdout.lastIndexOf('}');
  if (start === -1 || end === -1 || end < start) {
    throw new Error('load-chat output did not contain a JSON summary');
  }
  return JSON.parse(stdout.slice(start, end + 1));
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
