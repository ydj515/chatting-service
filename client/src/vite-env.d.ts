/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CHAT_API_BASE_URL?: string;
  readonly VITE_CHAT_API_TIMEOUT_MS?: string;
  readonly VITE_CHAT_WS_BASE_URL?: string;
  readonly VITE_CHAT_WS_MAX_RECONNECT_ATTEMPTS?: string;
  readonly VITE_CHAT_WS_RECONNECT_BASE_DELAY_MS?: string;
  readonly VITE_CHAT_WS_RECONNECT_MAX_DELAY_MS?: string;
  readonly VITE_CHAT_HEALTH_CHECK_INTERVAL_MS?: string;
  readonly VITE_CHAT_HEALTH_CHECK_BACKOFF_MULTIPLIER?: string;
  readonly VITE_CHAT_HEALTH_CHECK_MAX_INTERVAL_MS?: string;
  readonly VITE_CHAT_NOTIFICATION_DEDUP_WINDOW_MS?: string;
  readonly VITE_CHAT_NOTIFICATION_AUTO_REMOVE_MS?: string;
  readonly VITE_CHAT_ERROR_NOTIFICATION_AUTO_REMOVE_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
