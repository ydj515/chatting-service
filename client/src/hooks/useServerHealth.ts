import { useQuery } from '@tanstack/react-query';
import { appConfig } from '@/config/appConfig.ts';
import { healthApi } from '@/services/api.ts';
import { deriveServerHealthState, type ServerStatus } from '@/utils/serverHealth.ts';

export type { ServerStatus };

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

  return deriveServerHealthState({
    isPending: query.isPending,
    isError: query.isError,
    isRefetchError: query.isRefetchError,
    failureCount: query.failureCount,
    errorUpdatedAt: query.errorUpdatedAt,
  });
}
