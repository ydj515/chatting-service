import { expect, test, vi } from 'vitest';
import { logoutSession } from '@/utils/logoutSession.ts';

test('logout retains credentials until server revocation succeeds', async () => {
  const events: string[] = [];
  await logoutSession(async () => { events.push('revoke'); }, () => { events.push('clear'); });
  expect(events).toEqual(['revoke', 'clear']);
});

test('failed revocation preserves the session for an explicit retry', async () => {
  const clear = vi.fn();
  await expect(logoutSession(async () => { throw new Error('offline'); }, clear)).rejects.toThrow('offline');
  expect(clear).not.toHaveBeenCalled();
});
