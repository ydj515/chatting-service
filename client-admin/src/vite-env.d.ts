/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ADMIN_DEFAULT_BASE_URL?: string;
  readonly VITE_ADMIN_API_TIMEOUT_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
