// Phase 7 chaos test runner의 순수 로직.
// docker/HTTP I/O는 scripts/phase7-chaos-runner.mjs runner에만 두고, 여기서는
// 인자 파싱, 시나리오 정의, 체크 평가, summary 집계만 다룬다(테스트 가능 단위).

const KNOWN_CHECKS = ['health', 'functional', 'lag'];

const SCENARIOS = {
  'gateway-kill': { target: 'chat-websocket-app-1', injectAction: 'kill', sloMs: 30000 },
  'worker-kill': { target: 'chat-worker-app-1', injectAction: 'kill', sloMs: 30000 },
  'redis-restart': { target: 'redis', injectAction: 'restart', sloMs: 20000 },
  'replica-kill': { target: 'postgres-replica', injectAction: 'kill', sloMs: 30000 },
};

export function parseChaosArgs(argv, env = process.env) {
  const options = {
    scenario: null,
    target: null,
    execute: false,
    restore: true,
    recoveryTimeoutMs: positiveInteger(env.CHAT_PHASE7_CHAOS_RECOVERY_TIMEOUT_MS ?? '30000', 'recovery-timeout-ms'),
    scenarioSloMs: null,
    checks: [...KNOWN_CHECKS],
    lagThreshold: 0,
    pendingThreshold: 0,
    baseUrl: normalizeBaseUrl(env.CHAT_PHASE7_BASE_URL ?? 'http://localhost'),
    metricsUrl: env.CHAT_PHASE7_METRICS_URL ?? null,
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

    if (arg === '--scenario') {
      options.scenario = value;
    } else if (arg === '--target') {
      options.target = value;
    } else if (arg === '--recovery-timeout-ms') {
      options.recoveryTimeoutMs = positiveInteger(value, arg);
    } else if (arg === '--scenario-slo-ms') {
      options.scenarioSloMs = positiveInteger(value, arg);
    } else if (arg === '--checks') {
      options.checks = parseChecks(value);
    } else if (arg === '--lag-threshold') {
      options.lagThreshold = nonNegativeInteger(value, arg);
    } else if (arg === '--pending-threshold') {
      options.pendingThreshold = nonNegativeInteger(value, arg);
    } else if (arg === '--base-url') {
      options.baseUrl = normalizeBaseUrl(value);
    } else if (arg === '--metrics-url') {
      options.metricsUrl = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!options.scenario) {
    throw new Error('scenario is required (use --scenario <name>)');
  }
  if (!(options.scenario in SCENARIOS)) {
    throw new Error(`Unknown scenario: ${options.scenario}`);
  }

  return options;
}

export function buildChaosScenario({ scenario, target, scenarioSloMs }) {
  const definition = SCENARIOS[scenario];
  if (!definition) {
    throw new Error(`Unknown scenario: ${scenario}`);
  }
  return {
    name: scenario,
    target: target ?? definition.target,
    injectAction: definition.injectAction,
    sloMs: scenarioSloMs ?? definition.sloMs,
  };
}

export function buildInjectCommands(scenario, containerId, { restore }) {
  const inject = [scenario.injectAction, containerId];
  // restart는 컨테이너를 그대로 되살리므로 별도 restore가 필요 없다.
  // kill은 컨테이너를 정지시키므로 restore가 켜져 있으면 start로 되살린다.
  const needsRestore = restore && scenario.injectAction === 'kill';
  return {
    inject,
    restore: needsRestore ? ['start', containerId] : null,
  };
}

export function evaluateRecoveryCheck(check, observation) {
  const result = {
    check: check.check,
    required: check.required,
    recovered: observation.recovered === true,
    recoveryMs: observation.recovered === true ? observation.elapsedMs : null,
  };
  if (observation.lastValue !== undefined) {
    result.lastValue = observation.lastValue;
  }
  return result;
}

export function summarizeChaosRun({ scenario, injectedContainer, dryRun, sloMs, totalRecoveryMs, checks }) {
  const recoverySloMet = dryRun || totalRecoveryMs == null ? true : totalRecoveryMs <= sloMs;
  const requiredFailure = checks.some((check) => check.required && !check.recovered);
  const releaseBlocking = dryRun ? false : requiredFailure || !recoverySloMet;

  return {
    scenario,
    dryRun: dryRun === true,
    injectedContainer,
    totalRecoveryMs: totalRecoveryMs ?? null,
    sloMs,
    checks,
    recoverySloMet,
    releaseBlocking,
  };
}

export function exitCodeForChaosSummary(summary) {
  if (summary.dryRun) {
    return 0;
  }
  return summary.releaseBlocking ? 1 : 0;
}

function parseChecks(value) {
  const checks = value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  for (const check of checks) {
    if (!KNOWN_CHECKS.includes(check)) {
      throw new Error(`Unknown check: ${check} (known: ${KNOWN_CHECKS.join(', ')})`);
    }
  }
  if (checks.length === 0) {
    throw new Error('--checks must list at least one check');
  }
  return checks;
}

function normalizeBaseUrl(value) {
  return value.replace(/\/+$/, '');
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

export const KNOWN_CHAOS_SCENARIOS = Object.keys(SCENARIOS);
export const KNOWN_CHAOS_CHECKS = KNOWN_CHECKS;
