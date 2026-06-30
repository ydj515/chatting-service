import { useQuery } from '@tanstack/react-query';
import { appConfig } from '@/config/appConfig.ts';
import { healthApi } from '@/services/api.ts';

export type ServerStatus = 'checking' | 'online' | 'offline';

export function useServerHealth() {
  const query = useQuery({
    queryKey: ['server-health'],
    queryFn: healthApi.check,
    retry: false,
    refetchInterval: (queryState) => {
      const failures = queryState.state.fetchFailureCount;
      return Math.min(
        appConfig.healthCheck.intervalMs * Math.pow(appConfig.healthCheck.backoffMultiplier, failures),
        appConfig.healthCheck.maxIntervalMs,
      );
    },
  });

  const serverStatus: ServerStatus = query.isPending
    ? 'checking'
    : query.isError
      ? 'offline'
      : 'online';

  return {
    serverStatus,
    errorUpdatedAt: query.errorUpdatedAt,
    isError: query.isError,
  };
}
