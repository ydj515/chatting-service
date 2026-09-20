import { expect, test } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { clearAuthQueries, roomMessagesQueryKey } from '@/utils/authCache.ts';

test('account changes cannot reuse another user message cache', () => {
  const client = new QueryClient();
  client.setQueryData(roomMessagesQueryKey(1, 10), ['private']);
  expect(client.getQueryData(roomMessagesQueryKey(2, 10))).toBeUndefined();
  clearAuthQueries(client);
  expect(client.getQueryCache().getAll()).toHaveLength(0);
});

test('a late response cannot restore cleared account data', async () => {
  const client = new QueryClient();
  let resolve!: (value: string[]) => void;
  const pending = client.fetchQuery({ queryKey: roomMessagesQueryKey(1, 10), queryFn: () => new Promise<string[]>(r => { resolve = r; }) });
  const result = pending.catch(() => undefined);
  clearAuthQueries(client);
  resolve(['private']);
  await result;
  expect(client.getQueryCache().getAll()).toHaveLength(0);
});
