import type { User } from '@/types/index.ts';

export const isApiSessionReady = (
  currentUser: User | null,
  sessionToken: string | null,
  syncedSessionToken: string | null,
): boolean => {
  if (!currentUser) {
    return true;
  }

  return sessionToken !== null && syncedSessionToken === sessionToken;
};
