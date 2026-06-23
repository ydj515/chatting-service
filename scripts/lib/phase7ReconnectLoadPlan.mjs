const COHORTS = ['direct', 'nat_proxy', 'mobile_carrier', 'synthetic'];
const REASONS = ['network_flap', 'gateway_restart', 'gateway_kill', 'deploy', 'unknown'];

export function parseReconnectLoadArgs(argv, env = process.env) {
  const options = {
    scenario: 'baseline-reconnect',
    clients: 3,
    reconnectsPerClient: 2,
    cohort: 'synthetic',
    reason: 'network_flap',
    attemptSpacingMs: 1000,
    jitterMs: 250,
    minTicketIssueSuccessRatio: 0.999,
    minHandshakeSuccessRatio: 0.999,
    maxRateLimitFailureRatio: 0.001,
    maxCohortFailureRatio: 0.003,
    timeoutMs: positiveInteger(env.CHAT_PHASE7_RECONNECT_TIMEOUT_MS ?? '15000', 'CHAT_PHASE7_RECONNECT_TIMEOUT_MS'),
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--scenario') {
      options.scenario = value;
    } else if (arg === '--clients') {
      options.clients = positiveInteger(value, arg);
    } else if (arg === '--reconnects-per-client') {
      options.reconnectsPerClient = positiveInteger(value, arg);
    } else if (arg === '--cohort') {
      options.cohort = oneOf(value, COHORTS, arg);
    } else if (arg === '--reason') {
      options.reason = oneOf(value, REASONS, arg);
    } else if (arg === '--attempt-spacing-ms') {
      options.attemptSpacingMs = nonNegativeInteger(value, arg);
    } else if (arg === '--jitter-ms') {
      options.jitterMs = nonNegativeInteger(value, arg);
    } else if (arg === '--min-ticket-issue-success-ratio') {
      options.minTicketIssueSuccessRatio = ratio(value, arg);
    } else if (arg === '--min-handshake-success-ratio') {
      options.minHandshakeSuccessRatio = ratio(value, arg);
    } else if (arg === '--max-rate-limit-failure-ratio') {
      options.maxRateLimitFailureRatio = ratio(value, arg);
    } else if (arg === '--max-cohort-failure-ratio') {
      options.maxCohortFailureRatio = ratio(value, arg);
    } else if (arg === '--timeout-ms') {
      options.timeoutMs = positiveInteger(value, arg);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return options;
}

export function buildReconnectAttemptPlan({
  clients,
  reconnectsPerClient,
  attemptSpacingMs,
  jitterMs,
}) {
  const plan = [];
  for (let clientIndex = 0; clientIndex < clients; clientIndex += 1) {
    for (let attemptIndex = 0; attemptIndex < reconnectsPerClient; attemptIndex += 1) {
      plan.push({
        clientIndex,
        attemptIndex,
        delayMs: attemptIndex * attemptSpacingMs + deterministicJitter(clientIndex, attemptIndex, jitterMs),
      });
    }
  }
  return plan;
}

export function summarizeReconnectAttempts(events, gates) {
  const aggregate = events.reduce((acc, event) => {
    acc.normalReconnectAttempts += 1;
    if (event.ticketIssued) acc.normalReconnectTicketIssued += 1;
    if (event.rateLimited) acc.normalReconnectRateLimited += 1;
    if (event.handshakeSucceeded) acc.normalReconnectHandshakeSucceeded += 1;
    if (event.handshakeFailed) acc.normalReconnectHandshakeFailed += 1;
    return acc;
  }, {
    normalReconnectAttempts: 0,
    normalReconnectTicketIssued: 0,
    normalReconnectRateLimited: 0,
    normalReconnectHandshakeSucceeded: 0,
    normalReconnectHandshakeFailed: 0,
  });
  const cohorts = summarizeCohorts(events);
  const rates = {
    ticketIssueSuccessRate: ratioOrZero(
      aggregate.normalReconnectTicketIssued,
      aggregate.normalReconnectAttempts,
    ),
    rateLimitFailureRate: ratioOrZero(
      aggregate.normalReconnectRateLimited,
      aggregate.normalReconnectAttempts,
    ),
    handshakeSuccessRate: ratioOrZero(
      aggregate.normalReconnectHandshakeSucceeded,
      aggregate.normalReconnectTicketIssued,
    ),
  };
  const failedGates = [];
  if (rates.ticketIssueSuccessRate < gates.minTicketIssueSuccessRatio) {
    failedGates.push('ticket_issue_success_ratio');
  }
  if (rates.handshakeSuccessRate < gates.minHandshakeSuccessRatio) {
    failedGates.push('handshake_success_ratio');
  }
  if (rates.rateLimitFailureRate > gates.maxRateLimitFailureRatio) {
    failedGates.push('rate_limit_failure_ratio');
  }
  for (const [cohort, summary] of Object.entries(cohorts)) {
    if (summary.failureRate > gates.maxCohortFailureRatio) {
      failedGates.push(`cohort_failure_ratio:${cohort}`);
    }
  }

  return {
    ok: failedGates.length === 0,
    scenario: gates.scenario,
    durationSeconds: gates.durationSeconds,
    ...aggregate,
    rates,
    cohorts,
    failedGates,
  };
}

export function classifyTicketIssueFailure({ status, body }) {
  if (status === 429) {
    const normalized = String(body ?? '').toLowerCase();
    if (normalized.includes('ip')) {
      return 'rate_limited_ip';
    }
    if (!normalized.trim()) {
      return 'rate_limited';
    }
    return 'rate_limited_user';
  }
  if (status === 401 || status === 403) {
    return 'auth_failure';
  }
  return 'failure';
}

export function exitCodeForReconnectSummary(summary) {
  return summary.ok ? 0 : 1;
}

function summarizeCohorts(events) {
  const summaries = {};
  for (const event of events) {
    const cohort = event.cohort ?? 'synthetic';
    if (!summaries[cohort]) {
      summaries[cohort] = {
        attempts: 0,
        ticketIssued: 0,
        rateLimited: 0,
        handshakeSucceeded: 0,
        handshakeFailed: 0,
        failureRate: 0,
        rateLimitFailureRate: 0,
      };
    }
    const summary = summaries[cohort];
    summary.attempts += 1;
    if (event.ticketIssued) summary.ticketIssued += 1;
    if (event.rateLimited) summary.rateLimited += 1;
    if (event.handshakeSucceeded) summary.handshakeSucceeded += 1;
    if (event.handshakeFailed) summary.handshakeFailed += 1;
  }
  for (const summary of Object.values(summaries)) {
    const failures = summary.attempts - summary.handshakeSucceeded;
    summary.failureRate = ratioOrZero(failures, summary.attempts);
    summary.rateLimitFailureRate = ratioOrZero(summary.rateLimited, summary.attempts);
  }
  return summaries;
}

function deterministicJitter(clientIndex, attemptIndex, jitterMs) {
  if (jitterMs === 0) {
    return 0;
  }
  return (clientIndex * 31 + attemptIndex * 17) % (jitterMs + 1);
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

function ratioOrZero(count, total) {
  return total > 0 ? count / total : 0;
}
