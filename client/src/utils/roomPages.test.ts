import { expect, test, vi } from 'vitest';
import type { ChatRoom, PageResponse } from '@/types/index.ts';
import { loadRoomPages } from '@/utils/roomPages.ts';
const page = (ids: number[], last: boolean) => ({ content: ids.map(id => ({ id } as ChatRoom)), last } as PageResponse<ChatRoom>);

test('loads rooms beyond the first twenty and deduplicates page overlap', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(page(Array.from({length:20}, (_,i)=>i+1),false)).mockResolvedValueOnce(page([20,21],true));
  expect((await loadRoomPages(fetch)).map(r=>r.id)).toEqual(Array.from({length:21},(_,i)=>i+1));
  expect(fetch.mock.calls).toEqual([[0],[1]]);
});

test('does not return partial results when a later page fails', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(page([1],false)).mockRejectedValueOnce(new Error('offline'));
  await expect(loadRoomPages(fetch)).rejects.toThrow('offline');
});
