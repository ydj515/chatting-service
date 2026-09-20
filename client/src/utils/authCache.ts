import type { QueryClient } from '@tanstack/react-query';

export const clearAuthQueries = (client: QueryClient): void => {
  void client.cancelQueries();
  client.clear();
};

export const roomMessagesQueryKey = (userId: number, roomId: number) =>
  ['messages', userId, roomId] as const;
