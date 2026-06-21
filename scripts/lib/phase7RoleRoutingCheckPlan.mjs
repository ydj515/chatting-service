export function parseRoleRoutingCheckArgs(argv, env = process.env) {
  const options = {
    baseUrl: env.CHAT_PHASE7_BASE_URL ?? 'http://localhost',
    adminToken: env.CHAT_ADMIN_TOKEN ?? 'test',
    timeoutMs: positiveInteger(env.CHAT_PHASE7_ROUTE_TIMEOUT_MS ?? '3000', 'CHAT_PHASE7_ROUTE_TIMEOUT_MS'),
    json: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--json') {
      options.json = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--base-url') {
      options.baseUrl = value;
    } else if (arg === '--admin-token') {
      options.adminToken = value;
    } else if (arg === '--timeout-ms') {
      options.timeoutMs = positiveInteger(value, arg);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return {
    ...options,
    baseUrl: normalizeBaseUrl(options.baseUrl),
  };
}

export function buildRoleRoutingChecks({ baseUrl, adminToken, timeoutMs }) {
  const normalizedBaseUrl = normalizeBaseUrl(baseUrl);
  return [
    httpCheck({
      name: 'nginx-health',
      method: 'GET',
      path: '/health',
      expectedStatus: 200,
      baseUrl: normalizedBaseUrl,
      timeoutMs,
    }),
    httpCheck({
      name: 'api-ws-ticket-auth-required',
      method: 'POST',
      path: '/api/ws-tickets',
      expectedStatus: 400,
      baseUrl: normalizedBaseUrl,
      timeoutMs,
      headers: { 'content-type': 'application/json' },
      body: '',
      bodyIncludes: ['"status":400', '"/api/ws-tickets"'],
    }),
    httpCheck({
      name: 'admin-health',
      method: 'GET',
      path: '/api/admin/health',
      expectedStatus: 200,
      baseUrl: normalizedBaseUrl,
      timeoutMs,
      headers: { 'X-Admin-Token': adminToken },
      bodyIncludes: ['"status"', '"ok"'],
    }),
    {
      name: 'websocket-invalid-ticket-handshake',
      kind: 'websocket-handshake',
      method: 'GET',
      path: '/api/ws/chat?ticket=phase7-invalid-routing-ticket',
      expectedStatus: 401,
      url: new URL('/api/ws/chat?ticket=phase7-invalid-routing-ticket', `${normalizedBaseUrl}/`).toString(),
      timeoutMs,
    },
  ];
}

export function evaluateHttpCheckResponse(check, response) {
  if (response.status !== check.expectedStatus) {
    return checkResult(check, false, response.status, 'unexpected_status');
  }
  for (const expected of check.bodyIncludes ?? []) {
    if (!String(response.body ?? '').includes(expected)) {
      return checkResult(check, false, response.status, 'body_mismatch');
    }
  }
  return checkResult(check, true, response.status);
}

export function evaluateWebSocketHandshakeResponse(check, response) {
  if (response.status !== check.expectedStatus) {
    return checkResult(check, false, response.status, 'unexpected_status');
  }
  return checkResult(check, true, response.status);
}

export function buildWebSocketHandshakeRequestOptions(check, key) {
  const url = new URL(check.url);
  return {
    method: 'GET',
    protocol: url.protocol,
    hostname: url.hostname,
    port: url.port,
    path: `${url.pathname}${url.search}`,
    headers: {
      Host: url.host,
      Connection: 'Upgrade',
      Upgrade: 'websocket',
      'Sec-WebSocket-Key': key,
      'Sec-WebSocket-Version': '13',
    },
    timeout: check.timeoutMs,
  };
}

export function summarizeRoleRoutingChecks({ baseUrl, checks }) {
  const failed = checks.filter((check) => !check.ok).map((check) => check.name);
  return {
    ok: failed.length === 0,
    baseUrl: normalizeBaseUrl(baseUrl),
    checks,
    failed,
  };
}

export function exitCodeForSummary(summary) {
  return summary.ok ? 0 : 1;
}

function httpCheck({
  name,
  method,
  path,
  expectedStatus,
  baseUrl,
  timeoutMs,
  headers = {},
  body,
  bodyIncludes = [],
}) {
  return {
    name,
    kind: 'http',
    method,
    path,
    expectedStatus,
    url: new URL(path, `${baseUrl}/`).toString(),
    timeoutMs,
    headers,
    body,
    bodyIncludes,
  };
}

function checkResult(check, ok, status, reason) {
  return {
    name: check.name,
    method: check.method,
    url: check.url,
    ok,
    status,
    ...(reason ? { reason } : {}),
  };
}

function normalizeBaseUrl(value) {
  return String(value).replace(/\/+$/, '');
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}
