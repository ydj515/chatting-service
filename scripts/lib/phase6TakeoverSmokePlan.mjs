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
    verifyRoutingAfterRestore: false,
    routingCheckBaseUrl: process.env.CHAT_PHASE7_BASE_URL ?? 'http://localhost',
    routingCheckAdminToken: process.env.CHAT_ADMIN_TOKEN ?? 'test',
    // routing check를 실제로 쓸 때만 검증한다(아래 참고). opt-in이 아닐 때 잘못된 env 값으로 Phase 6를 깨뜨리지 않기 위함이다.
    routingCheckTimeoutMs: process.env.CHAT_PHASE7_ROUTE_TIMEOUT_MS ?? '3000',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--verify-routing-after-restore') {
      options.verifyRoutingAfterRestore = true;
      continue;
    }

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
    } else if (arg === '--routing-check-base-url') {
      options.routingCheckBaseUrl = value;
    } else if (arg === '--routing-check-admin-token') {
      options.routingCheckAdminToken = value;
    } else if (arg === '--routing-check-timeout-ms') {
      options.routingCheckTimeoutMs = positiveInteger(value, arg);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  // timeout 검증은 routing check를 실제로 실행하는 경우에만 수행한다.
  if (options.verifyRoutingAfterRestore) {
    options.routingCheckTimeoutMs = positiveInteger(
      options.routingCheckTimeoutMs,
      'CHAT_PHASE7_ROUTE_TIMEOUT_MS',
    );
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
    '--takeover-delivery-summary',
  ];
}

export function buildTakeoverSmokeSummary({ killedContainer, loadSummary }) {
  const releaseBlocking = loadSummary.takeoverDelivery?.releaseBlocking === true;
  return {
    ok: !releaseBlocking,
    killedContainer: killedContainer.name,
    killedContainerId: killedContainer.id.slice(0, 12),
    roomId: loadSummary.roomId,
    sent: loadSummary.sent,
    receivedPerViewer: loadSummary.receivedPerViewer,
    minReceivedRatio: loadSummary.minReceivedRatio,
    assertedRoomSeqOrder: loadSummary.assertedRoomSeqOrder,
    takeoverDeliverySummary: loadSummary.takeoverDeliverySummary === true,
    ...(loadSummary.takeoverDelivery ? { takeoverDelivery: loadSummary.takeoverDelivery } : {}),
  };
}

export function buildRoutingCheckArgs(options) {
  // admin token은 프로세스 목록에 노출되지 않도록 CLI 인자 대신 CHAT_ADMIN_TOKEN env로 전달한다.
  return [
    '--base-url',
    options.routingCheckBaseUrl,
    '--timeout-ms',
    String(options.routingCheckTimeoutMs),
    '--json',
  ];
}

export function coerceRoutingCheckOutput(output) {
  if (output == null) {
    return '';
  }
  return Buffer.isBuffer(output) ? output.toString('utf8') : String(output);
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
  const end = stdout.lastIndexOf('}');
  if (end === -1) {
    throw new Error('load-chat output did not contain a JSON summary');
  }
  for (let start = stdout.lastIndexOf('{', end); start !== -1; start = stdout.lastIndexOf('{', start - 1)) {
    try {
      return JSON.parse(stdout.slice(start, end + 1));
    } catch {
      // Keep walking left until the outer JSON object is found.
    }
  }
  throw new Error('load-chat output did not contain a JSON summary');
}

export function buildRunCapturedOptions({ env, timeoutMs = 30_000 }) {
  return {
    env,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: timeoutMs,
  };
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
