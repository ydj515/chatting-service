import React, { useCallback } from 'react';
import { useMutation } from '@tanstack/react-query';
import {
  createAdminExport,
  fetchAdminExportStatus,
  fetchAdminHistory,
  fetchAdminRoomStatus,
  searchAdminMessages,
} from '@/services/adminApi.ts';
import type { AdminFilters, AdminMessagePage, AdminRoomStatus } from '@/types/index.ts';
import AdminControls from '@/components/AdminControls.tsx';
import type { AdvancedSetters, AdvancedValues } from '@/components/AdvancedFilters.tsx';
import MessageTable from '@/components/MessageTable.tsx';
import RoomStatus from '@/components/RoomStatus.tsx';
import Layout from '@/components/layout/Layout.tsx';
import { useAdminStore } from '@/stores/adminStore.ts';
import { isValidRoomId, numberOrNull } from '@/utils/adminValidation.ts';

interface RefreshVariables {
  baseUrl: string;
  token: string;
  roomId: string;
  filters: AdminFilters;
}

interface RefreshResult {
  status: AdminRoomStatus;
  historyPage: AdminMessagePage;
  searchPage: AdminMessagePage;
}

interface PageVariables {
  baseUrl: string;
  token: string;
  roomId: string;
  filters: AdminFilters;
}

interface ExportVariables {
  baseUrl: string;
  token: string;
  filters: AdminFilters;
}

interface ExportStatusVariables {
  baseUrl: string;
  token: string;
  jobId: string;
}

function AdminPage() {
  const baseUrl = useAdminStore((state) => state.baseUrl);
  const token = useAdminStore((state) => state.token);
  const roomId = useAdminStore((state) => state.roomId);
  const query = useAdminStore((state) => state.query);
  const searchMode = useAdminStore((state) => state.searchMode);
  const senderId = useAdminStore((state) => state.senderId);
  const from = useAdminStore((state) => state.from);
  const to = useAdminStore((state) => state.to);
  const limit = useAdminStore((state) => state.limit);
  const advancedOpen = useAdminStore((state) => state.advancedOpen);
  const status = useAdminStore((state) => state.status);
  const historyRows = useAdminStore((state) => state.historyRows);
  const historyLatency = useAdminStore((state) => state.historyLatency);
  const historyHasNext = useAdminStore((state) => state.historyHasNext);
  const historyCursor = useAdminStore((state) => state.historyCursor);
  const searchRows = useAdminStore((state) => state.searchRows);
  const searchLatency = useAdminStore((state) => state.searchLatency);
  const searchHasNext = useAdminStore((state) => state.searchHasNext);
  const searchCursor = useAdminStore((state) => state.searchCursor);
  const lastExportJob = useAdminStore((state) => state.lastExportJob);
  const exportJobContext = useAdminStore((state) => state.exportJobContext);
  const notice = useAdminStore((state) => state.notice);
  const noticeError = useAdminStore((state) => state.noticeError);
  const setBaseUrl = useAdminStore((state) => state.setBaseUrl);
  const setToken = useAdminStore((state) => state.setToken);
  const setRoomId = useAdminStore((state) => state.setRoomId);
  const setQuery = useAdminStore((state) => state.setQuery);
  const setSearchMode = useAdminStore((state) => state.setSearchMode);
  const setSenderId = useAdminStore((state) => state.setSenderId);
  const setFrom = useAdminStore((state) => state.setFrom);
  const setTo = useAdminStore((state) => state.setTo);
  const setLimit = useAdminStore((state) => state.setLimit);
  const setAdvancedOpen = useAdminStore((state) => state.setAdvancedOpen);
  const persistState = useAdminStore((state) => state.persistState);
  const showNotice = useAdminStore((state) => state.showNotice);
  const setStatus = useAdminStore((state) => state.setStatus);
  const setHistoryPage = useAdminStore((state) => state.setHistoryPage);
  const setSearchPage = useAdminStore((state) => state.setSearchPage);
  const setLastExportJob = useAdminStore((state) => state.setLastExportJob);
  const setExportJobContext = useAdminStore((state) => state.setExportJobContext);

  const advancedValues: AdvancedValues = { senderId, from, to, limit };
  const advancedSetters: AdvancedSetters = {
    senderId: setSenderId,
    from: setFrom,
    to: setTo,
    limit: setLimit,
  };

  const buildFilters = useCallback((): AdminFilters => {
    return {
      query: query.trim(),
      mode: searchMode,
      roomId: numberOrNull(roomId),
      senderId: numberOrNull(senderId),
      from,
      to,
      limit: Number(limit || 50),
    };
  }, [query, searchMode, roomId, senderId, from, to, limit]);

  const showMutationError = useCallback(
    (error: unknown) => {
      showNotice(error instanceof Error ? error.message : String(error), true);
    },
    [showNotice],
  );

  const refreshMutation = useMutation({
    mutationFn: async ({ baseUrl: effectiveBaseUrl, token: adminToken, roomId: effectiveRoomId, filters }: RefreshVariables): Promise<RefreshResult> => {
      const [nextStatus, historyPage, searchPage] = await Promise.all([
        fetchAdminRoomStatus(effectiveBaseUrl, adminToken, Number(effectiveRoomId)),
        fetchAdminHistory(effectiveBaseUrl, adminToken, Number(effectiveRoomId), filters),
        searchAdminMessages(effectiveBaseUrl, adminToken, filters),
      ]);
      return { status: nextStatus, historyPage, searchPage };
    },
    onSuccess: (result) => {
      setStatus(result.status);
      setHistoryPage(result.historyPage);
      setSearchPage(result.searchPage);
      showNotice('Admin data loaded');
    },
    onError: showMutationError,
  });

  const historyNextMutation = useMutation({
    mutationFn: ({ baseUrl: effectiveBaseUrl, token: adminToken, roomId: effectiveRoomId, filters }: PageVariables) =>
      fetchAdminHistory(effectiveBaseUrl, adminToken, Number(effectiveRoomId), filters),
    onSuccess: (page) => {
      setHistoryPage(page);
      showNotice('Admin data loaded');
    },
    onError: showMutationError,
  });

  const searchNextMutation = useMutation({
    mutationFn: ({ baseUrl: effectiveBaseUrl, token: adminToken, filters }: PageVariables) =>
      searchAdminMessages(effectiveBaseUrl, adminToken, filters),
    onSuccess: (page) => {
      setSearchPage(page);
      showNotice('Admin data loaded');
    },
    onError: showMutationError,
  });

  const exportMutation = useMutation({
    mutationFn: ({ baseUrl: effectiveBaseUrl, token: adminToken, filters }: ExportVariables) =>
      createAdminExport(effectiveBaseUrl, adminToken, filters),
    onSuccess: (job, variables) => {
      setLastExportJob(job);
      setExportJobContext({ baseUrl: variables.baseUrl, token: variables.token });
      showNotice(`Export ${job.jobId} ${job.status}`);
    },
    onError: showMutationError,
  });

  const exportStatusMutation = useMutation({
    mutationFn: ({ baseUrl: jobBaseUrl, token: jobToken, jobId }: ExportStatusVariables) =>
      fetchAdminExportStatus(jobBaseUrl, jobToken, jobId),
    onSuccess: (job) => {
      setLastExportJob(job);
      showNotice('Export status loaded');
    },
    onError: showMutationError,
  });

  const busy =
    refreshMutation.isPending ||
    historyNextMutation.isPending ||
    searchNextMutation.isPending ||
    exportMutation.isPending ||
    exportStatusMutation.isPending;

  const handleRefresh = (event: React.FormEvent) => {
    event.preventDefault();
    const { baseUrl: effectiveBaseUrl, roomId: effectiveRoomId } = persistState(localStorage);
    if (!isValidRoomId(effectiveRoomId)) {
      showNotice('Room ID는 숫자여야 합니다.', true);
      return;
    }
    refreshMutation.mutate({
      baseUrl: effectiveBaseUrl,
      token,
      roomId: effectiveRoomId,
      filters: buildFilters(),
    });
  };

  const handleExport = () => {
    const { baseUrl: effectiveBaseUrl } = persistState(localStorage);
    exportMutation.mutate({
      baseUrl: effectiveBaseUrl,
      token,
      filters: buildFilters(),
    });
  };

  const handleExportStatusRefresh = () => {
    if (!lastExportJob || !exportJobContext) return;
    exportStatusMutation.mutate({
      baseUrl: exportJobContext.baseUrl,
      token: exportJobContext.token,
      jobId: lastExportJob.jobId,
    });
  };

  const handleHistoryNext = () => {
    if (!historyCursor) return;
    const effectiveRoomId = roomId.trim() || '1';
    if (!isValidRoomId(effectiveRoomId)) {
      showNotice('Room ID는 숫자여야 합니다.', true);
      return;
    }
    historyNextMutation.mutate({
      baseUrl,
      token,
      roomId: effectiveRoomId,
      filters: {
        ...buildFilters(),
        roomId: numberOrNull(effectiveRoomId),
        cursor: historyCursor,
      },
    });
  };

  const handleSearchNext = () => {
    if (!searchCursor) return;
    searchNextMutation.mutate({
      baseUrl,
      token,
      roomId,
      filters: {
        ...buildFilters(),
        cursor: searchCursor,
      },
    });
  };

  return (
    <Layout
      notice={notice}
      noticeError={noticeError}
    >
      <AdminControls
        baseUrl={baseUrl}
        token={token}
        roomId={roomId}
        query={query}
        searchMode={searchMode}
        advancedOpen={advancedOpen}
        advancedValues={advancedValues}
        advancedSetters={advancedSetters}
        busy={busy}
        lastExportJob={lastExportJob}
        onBaseUrlChange={setBaseUrl}
        onTokenChange={setToken}
        onRoomIdChange={setRoomId}
        onQueryChange={setQuery}
        onSearchModeChange={setSearchMode}
        onAdvancedOpenChange={setAdvancedOpen}
        onRefresh={handleRefresh}
        onExport={handleExport}
        onExportStatusRefresh={handleExportStatusRefresh}
      />

      <section className="grid gap-4 min-w-0">
        <RoomStatus status={status} />
        <MessageTable
          title="Room History"
          latencyMs={historyLatency}
          rows={historyRows}
          hasNext={historyHasNext}
          onNext={handleHistoryNext}
        />
        <MessageTable
          title="Message Search"
          latencyMs={searchLatency}
          rows={searchRows}
          hasNext={searchHasNext}
          onNext={handleSearchNext}
        />
      </section>
    </Layout>
  );
}

export default AdminPage;
