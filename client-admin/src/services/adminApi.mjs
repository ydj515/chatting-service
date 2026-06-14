const DEFAULT_MAX_LIMIT = 100;

export function createAdminHeaders(adminToken) {
  return {
    'Content-Type': 'application/json',
    'X-Admin-Token': adminToken,
  };
}

export function buildAdminHistoryUrl(baseUrl, roomId, filters = {}) {
  const params = new URLSearchParams();
  appendOptional(params, 'from', filters.from);
  appendOptional(params, 'to', filters.to);
  appendOptional(params, 'cursor', filters.cursor);
  params.set('limit', String(boundedLimit(filters.limit)));
  return `${normalizeBaseUrl(baseUrl)}/admin/chat-rooms/${encodeURIComponent(String(roomId))}/messages?${params}`;
}

export function buildAdminSearchUrl(baseUrl, filters = {}) {
  const params = new URLSearchParams();
  params.set('q', filters.query ?? '');
  appendOptional(params, 'mode', filters.mode);
  appendOptional(params, 'roomId', filters.roomId);
  appendOptional(params, 'from', filters.from);
  appendOptional(params, 'to', filters.to);
  appendOptional(params, 'senderId', filters.senderId);
  appendOptional(params, 'cursor', filters.cursor);
  params.set('limit', String(boundedLimit(filters.limit)));
  return `${normalizeBaseUrl(baseUrl)}/admin/messages/search?${params}`;
}

export function buildAdminRoomStatusUrl(baseUrl, roomId) {
  return `${normalizeBaseUrl(baseUrl)}/admin/rooms/${encodeURIComponent(String(roomId))}/status`;
}

export function buildAdminExportUrl(baseUrl) {
  return `${normalizeBaseUrl(baseUrl)}/admin/exports/messages`;
}

export async function fetchAdminHistory(baseUrl, adminToken, roomId, filters) {
  return requestJson(buildAdminHistoryUrl(baseUrl, roomId, filters), {
    headers: createAdminHeaders(adminToken),
  });
}

export async function searchAdminMessages(baseUrl, adminToken, filters) {
  return requestJson(buildAdminSearchUrl(baseUrl, filters), {
    headers: createAdminHeaders(adminToken),
  });
}

export async function fetchAdminRoomStatus(baseUrl, adminToken, roomId) {
  return requestJson(buildAdminRoomStatusUrl(baseUrl, roomId), {
    headers: createAdminHeaders(adminToken),
  });
}

export async function createAdminExport(baseUrl, adminToken, payload) {
  return requestJson(buildAdminExportUrl(baseUrl), {
    method: 'POST',
    headers: createAdminHeaders(adminToken),
    body: JSON.stringify(payload),
  });
}

async function requestJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`Admin request failed: ${response.status} ${detail}`);
  }
  return response.json();
}

function appendOptional(params, key, value) {
  if (value !== undefined && value !== null && value !== '') {
    params.set(key, String(value));
  }
}

function boundedLimit(value, maxLimit = DEFAULT_MAX_LIMIT) {
  const parsed = Number.parseInt(String(value ?? 50), 10);
  if (!Number.isFinite(parsed)) {
    return 50;
  }
  return Math.min(Math.max(parsed, 1), maxLimit);
}

function normalizeBaseUrl(baseUrl) {
  return (baseUrl || '/api').replace(/\/+$/, '');
}
