// Phase 7 reconnect chaos orchestrator의 순수 로직.
// docker/HTTP I/O와 child spawn은 scripts/phase7-reconnect-chaos.mjs runner에만 두고,
// 여기서는 인자 파싱, gateway fault step 빌드, reconnect-load 인자 빌드, summary 집계만 다룬다.
import { RECONNECT_COHORTS, RECONNECT_REASONS } from './phase7ReconnectLoadPlan.mjs';

// 각 fault 모드의 기본 대상/주입 동작/reconnect reason. gateway는 Compose에서 2 replica이고
// container_name이 고정이라 service 단위로 docker restart/kill/start 한다.
const FAULT_MODES = {
  'gateway-rolling-restart': {
    gateways: ['chat-websocket-app-1', 'chat-websocket-app-2'],
    injectAction: 'restart',
    reason: 'gateway_restart',
  },
  'gateway-hard-kill': {
    gateways: ['chat-websocket-app-1'],
    injectAction: 'kill',
    reason: 'gateway_kill',
  },
};

// reconnect-load child로 그대로 전달할 gate 비율. 미지정 시 null로 두어 child 기본값을 따른다.
const GATE_RATIO_FLAGS = [
  ['minTicketIssueSuccessRatio', '--min-ticket-issue-success-ratio'],
  ['minHandshakeSuccessRatio', '--min-handshake-success-ratio'],
  ['maxRateLimitFailureRatio', '--max-rate-limit-failure-ratio'],
  ['maxCohortFailureRatio', '--max-cohort-failure-ratio'],
];

export function parseReconnectChaosArgs(argv) {
  const options = {
    faultMode: null,
    gateways: null,
    execute: false,
    restore: true,
    rollingStepDelayMs: 2000,
    injectAfterMs: 0,
    readyTimeoutMs: 60000,
    // reconnect-load pass-through. storm이 fault window보다 길도록 chaos 기본값을 키운다.
    clients: 5,
    reconnectsPerClient: 6,
    attemptSpacingMs: 1000,
    jitterMs: 250,
    cohort: 'synthetic',
    reason: null,
    scenario: null,
    minTicketIssueSuccessRatio: null,
    minHandshakeSuccessRatio: null,
    maxRateLimitFailureRatio: null,
    maxCohortFailureRatio: null,
    json: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--execute') {
      options.execute = true;
      continue;
    }
    if (arg === '--no-restore') {
      options.restore = false;
      continue;
    }
    if (arg === '--json') {
      options.json = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--fault') {
      options.faultMode = value;
    } else if (arg === '--gateways') {
      options.gateways = parseGateways(value);
    } else if (arg === '--rolling-step-delay-ms') {
      options.rollingStepDelayMs = nonNegativeInteger(value, arg);
    } else if (arg === '--inject-after-ms') {
      options.injectAfterMs = nonNegativeInteger(value, arg);
    } else if (arg === '--ready-timeout-ms') {
      options.readyTimeoutMs = positiveInteger(value, arg);
    } else if (arg === '--clients') {
      options.clients = positiveInteger(value, arg);
    } else if (arg === '--reconnects-per-client') {
      options.reconnectsPerClient = positiveInteger(value, arg);
    } else if (arg === '--attempt-spacing-ms') {
      options.attemptSpacingMs = nonNegativeInteger(value, arg);
    } else if (arg === '--jitter-ms') {
      options.jitterMs = nonNegativeInteger(value, arg);
    } else if (arg === '--cohort') {
      options.cohort = oneOf(value, RECONNECT_COHORTS, arg);
    } else if (arg === '--reason') {
      options.reason = oneOf(value, RECONNECT_REASONS, arg);
    } else if (arg === '--scenario') {
      options.scenario = value;
    } else if (arg === '--min-ticket-issue-success-ratio') {
      options.minTicketIssueSuccessRatio = ratio(value, arg);
    } else if (arg === '--min-handshake-success-ratio') {
      options.minHandshakeSuccessRatio = ratio(value, arg);
    } else if (arg === '--max-rate-limit-failure-ratio') {
      options.maxRateLimitFailureRatio = ratio(value, arg);
    } else if (arg === '--max-cohort-failure-ratio') {
      options.maxCohortFailureRatio = ratio(value, arg);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!options.faultMode) {
    throw new Error('--fault is required (use --fault <gateway-rolling-restart|gateway-hard-kill>)');
  }
  const mode = FAULT_MODES[options.faultMode];
  if (!mode) {
    throw new Error(`Unknown fault mode: ${options.faultMode}`);
  }

  // 미지정 항목은 fault 모드 기본값으로 채운다.
  if (options.gateways === null) {
    options.gateways = [...mode.gateways];
  }
  if (options.reason === null) {
    options.reason = mode.reason;
  }
  if (options.scenario === null) {
    options.scenario = options.faultMode;
  }

  return options;
}

export function buildGatewayFaultPlan(options) {
  const mode = FAULT_MODES[options.faultMode];
  if (!mode) {
    throw new Error(`Unknown fault mode: ${options.faultMode}`);
  }
  // restart는 컨테이너를 그대로 되살리므로 별도 복구가 필요 없다.
  // kill은 컨테이너를 정지시키므로 restore가 켜져 있으면 복구가 필요하다.
  const restoreNeeded = mode.injectAction === 'kill' && options.restore;
  return options.gateways.map((service) => ({
    action: mode.injectAction,
    service,
    restoreNeeded,
  }));
}

export function buildReconnectLoadArgs(options) {
  const args = [
    '--scenario', options.scenario,
    '--reason', options.reason,
    '--clients', String(options.clients),
    '--reconnects-per-client', String(options.reconnectsPerClient),
    '--cohort', options.cohort,
    '--attempt-spacing-ms', String(options.attemptSpacingMs),
    '--jitter-ms', String(options.jitterMs),
  ];
  for (const [field, flag] of GATE_RATIO_FLAGS) {
    if (options[field] !== null && options[field] !== undefined) {
      args.push(flag, String(options[field]));
    }
  }
  return args;
}

export function summarizeReconnectChaos({
  faultMode,
  injectedContainers,
  injectionOffsetMs,
  reconnectSummary,
  dryRun,
}) {
  const reconnect = reconnectSummary ?? null;
  const releaseBlocking = dryRun ? false : !(reconnect?.ok === true);
  return {
    faultMode,
    dryRun: dryRun === true,
    injectedContainers,
    injectionOffsetMs: dryRun ? null : (injectionOffsetMs ?? null),
    reconnect,
    failedGates: reconnect?.failedGates ?? [],
    releaseBlocking,
  };
}

export function exitCodeForReconnectChaosSummary(summary) {
  if (summary.dryRun) {
    return 0;
  }
  return summary.releaseBlocking ? 1 : 0;
}

function parseGateways(value) {
  const gateways = value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  if (gateways.length === 0) {
    throw new Error('--gateways must list at least one gateway service');
  }
  return gateways;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function nonNegativeInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`${name} must be a non-negative integer`);
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

function oneOf(value, allowed, name) {
  if (!allowed.includes(value)) {
    throw new Error(`${name} must be one of ${allowed.join(', ')}`);
  }
  return value;
}

export const KNOWN_FAULT_MODES = Object.keys(FAULT_MODES);
