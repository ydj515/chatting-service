import { afterEach, expect, test, vi } from 'vitest';
vi.mock('@/config/appConfig.ts', () => ({ appConfig: { api: { baseUrl: '/api', timeoutMs: 1000 } } }));
import api, { userApi } from '@/services/api.ts';
afterEach(() => vi.restoreAllMocks());

test('an expired session can still complete local logout', async () => {
  vi.spyOn(api, 'post').mockRejectedValue({ isAxiosError: true, response: { status: 401 } });
  await expect(userApi.logout()).resolves.toBeUndefined();
});

test('a revocation outage is not reported as successful logout', async () => {
  const failure = { isAxiosError: true, response: { status: 503 } };
  vi.spyOn(api, 'post').mockRejectedValue(failure);
  await expect(userApi.logout()).rejects.toBe(failure);
});
