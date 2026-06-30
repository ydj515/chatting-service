export type ServerStatus = 'checking' | 'online' | 'offline';

interface ServerHealthSnapshot {
  isPending: boolean;
  isError: boolean;
  isRefetchError: boolean;
  failureCount: number;
  errorUpdatedAt: number;
}

interface ServerHealthState {
  serverStatus: ServerStatus;
  errorUpdatedAt: number;
  isError: boolean;
}

export const deriveServerHealthState = (snapshot: ServerHealthSnapshot): ServerHealthState => {
  const isOffline = snapshot.isError || snapshot.isRefetchError || snapshot.failureCount > 0;

  return {
    serverStatus: snapshot.isPending ? 'checking' : isOffline ? 'offline' : 'online',
    errorUpdatedAt: snapshot.errorUpdatedAt,
    isError: isOffline,
  };
};
