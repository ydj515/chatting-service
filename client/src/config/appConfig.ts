const DEFAULT_API_BASE_PATH = '/api';
const DEFAULT_WS_PATH = '/api/ws/chat';
const DEFAULT_API_TIMEOUT_MS = 30_000;
const DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
const DEFAULT_RECONNECT_BASE_DELAY_MS = 1_000;
const DEFAULT_RECONNECT_MAX_DELAY_MS = 30_000;
const DEFAULT_HEALTH_CHECK_INTERVAL_MS = 30_000;
const DEFAULT_HEALTH_CHECK_BACKOFF_MULTIPLIER = 1.5;
const DEFAULT_HEALTH_CHECK_MAX_INTERVAL_MS = 300_000;
const DEFAULT_NOTIFICATION_DEDUP_WINDOW_MS = 30_000;
const DEFAULT_NOTIFICATION_AUTO_REMOVE_MS = 3_000;
const DEFAULT_ERROR_NOTIFICATION_AUTO_REMOVE_MS = 10_000;

const trimTrailingSlash = (value: string): string => value.replace(/\/+$/, '');

const numberFromEnv = (value: string | undefined, fallback: number): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
};

const currentWebSocketOrigin = (): string => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}`;
};

const defaultWebSocketBaseUrl = (): string => `${currentWebSocketOrigin()}${DEFAULT_WS_PATH}`;

const normalizeWebSocketBaseUrl = (value: string): string => {
  if (value.startsWith('ws://') || value.startsWith('wss://')) {
    return value;
  }

  const path = value.startsWith('/') ? value : `/${value}`;
  return `${currentWebSocketOrigin()}${path}`;
};

export const appConfig = {
  api: {
    baseUrl: trimTrailingSlash(import.meta.env.VITE_CHAT_API_BASE_URL ?? DEFAULT_API_BASE_PATH),
    timeoutMs: numberFromEnv(import.meta.env.VITE_CHAT_API_TIMEOUT_MS, DEFAULT_API_TIMEOUT_MS),
  },
  webSocket: {
    baseUrl: trimTrailingSlash(
      normalizeWebSocketBaseUrl(import.meta.env.VITE_CHAT_WS_BASE_URL ?? defaultWebSocketBaseUrl()),
    ),
    maxReconnectAttempts: numberFromEnv(
      import.meta.env.VITE_CHAT_WS_MAX_RECONNECT_ATTEMPTS,
      DEFAULT_MAX_RECONNECT_ATTEMPTS,
    ),
    reconnectBaseDelayMs: numberFromEnv(
      import.meta.env.VITE_CHAT_WS_RECONNECT_BASE_DELAY_MS,
      DEFAULT_RECONNECT_BASE_DELAY_MS,
    ),
    reconnectMaxDelayMs: numberFromEnv(
      import.meta.env.VITE_CHAT_WS_RECONNECT_MAX_DELAY_MS,
      DEFAULT_RECONNECT_MAX_DELAY_MS,
    ),
    normalCloseCodes: [1000, 1001],
  },
  healthCheck: {
    intervalMs: numberFromEnv(
      import.meta.env.VITE_CHAT_HEALTH_CHECK_INTERVAL_MS,
      DEFAULT_HEALTH_CHECK_INTERVAL_MS,
    ),
    backoffMultiplier: numberFromEnv(
      import.meta.env.VITE_CHAT_HEALTH_CHECK_BACKOFF_MULTIPLIER,
      DEFAULT_HEALTH_CHECK_BACKOFF_MULTIPLIER,
    ),
    maxIntervalMs: numberFromEnv(
      import.meta.env.VITE_CHAT_HEALTH_CHECK_MAX_INTERVAL_MS,
      DEFAULT_HEALTH_CHECK_MAX_INTERVAL_MS,
    ),
  },
  notification: {
    dedupWindowMs: numberFromEnv(
      import.meta.env.VITE_CHAT_NOTIFICATION_DEDUP_WINDOW_MS,
      DEFAULT_NOTIFICATION_DEDUP_WINDOW_MS,
    ),
    autoRemoveMs: numberFromEnv(
      import.meta.env.VITE_CHAT_NOTIFICATION_AUTO_REMOVE_MS,
      DEFAULT_NOTIFICATION_AUTO_REMOVE_MS,
    ),
    errorAutoRemoveMs: numberFromEnv(
      import.meta.env.VITE_CHAT_ERROR_NOTIFICATION_AUTO_REMOVE_MS,
      DEFAULT_ERROR_NOTIFICATION_AUTO_REMOVE_MS,
    ),
  },
};

export const buildWebSocketUrl = (userId: number): string => {
  const url = new URL(appConfig.webSocket.baseUrl);
  url.searchParams.set('userId', userId.toString());
  return url.toString();
};
