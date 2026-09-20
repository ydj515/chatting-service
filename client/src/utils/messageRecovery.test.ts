import { expect, test, vi } from 'vitest';
import type { Message } from '@/types/index.ts';
import { recoverMessages } from '@/utils/messageRecovery.ts';
const message = (seq: number) => ({ messageId: `m${seq}`, roomSeq: seq, sequenceNumber: seq, createdAt: '2026-09-21T00:00:00Z' } as Message);

test('reconnect follows gap pages and deduplicates the latest overlap', async () => {
  const gap = vi.fn().mockResolvedValueOnce(Array.from({length: 100}, (_, i) => message(i + 2))).mockResolvedValueOnce([message(102)]);
  const recovered = await recoverMessages([message(1)], async () => [message(102), message(103)], gap);
  expect(gap.mock.calls).toEqual([[1, 100], [101, 100]]);
  expect(recovered).toHaveLength(102);
});

test('latest overlap repairs an older sequence that persisted after live delivery', async () => {
  const recovered = await recoverMessages([message(3)], async () => [message(2), message(3)], async () => []);
  expect(recovered.map(m => m.roomSeq)).toEqual([2, 3]);
});

test('recovery propagates failure so the caller can retry without advancing state', async () => {
  await expect(recoverMessages([message(1)], async () => [], async () => { throw new Error('offline'); })).rejects.toThrow('offline');
});
