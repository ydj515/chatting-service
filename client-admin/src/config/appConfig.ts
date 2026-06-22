const DEFAULT_API_BASE_PATH = '/api';
const DEFAULT_API_TIMEOUT_MS = 30_000;

const trimTrailingSlash = (value: string): string => value.replace(/\/+$/, '');

const numberFromEnv = (value: string | undefined, fallback: number): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
};

// vite 번들에서는 import.meta.env가 주입되지만, node --test(strip-types)에서는 없으므로 옵셔널 체이닝으로 보호한다.
const env = import.meta.env ?? {};

// 빌드 타임에 주입된 기본 base-url(예: dev 모드의 http://localhost/api).
// 주입값이 없으면 상대경로 '/api'로 폴백한다.
const injectedBaseUrl = (env.VITE_ADMIN_DEFAULT_BASE_URL ?? '').trim();

export const appConfig = {
  api: {
    defaultBaseUrl: trimTrailingSlash(injectedBaseUrl || DEFAULT_API_BASE_PATH),
    timeoutMs: numberFromEnv(env.VITE_ADMIN_API_TIMEOUT_MS, DEFAULT_API_TIMEOUT_MS),
  },
};
