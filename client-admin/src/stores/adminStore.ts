import { create } from 'zustand';
import { appConfig } from '@/config/appConfig.ts';
import { loadAdminState, saveAdminState } from '@/services/adminState.ts';
import type {
  AdminExportJob,
  AdminMessage,
  AdminMessagePage,
  AdminRoomStatus,
  SearchMode,
} from '@/types/index.ts';

interface ExportJobContext {
  baseUrl: string;
  token: string;
}

const initialAdminState = () => {
  try {
    return loadAdminState(localStorage, appConfig.api.defaultBaseUrl);
  } catch {
    return loadAdminState(
      {
        getItem: () => null,
        setItem: () => undefined,
      },
      appConfig.api.defaultBaseUrl,
    );
  }
};

interface AdminStore {
  baseUrl: string;
  token: string;
  roomId: string;
  query: string;
  searchMode: SearchMode;
  senderId: string;
  from: string;
  to: string;
  limit: string;
  advancedOpen: boolean;
  status: AdminRoomStatus | null;
  historyRows: AdminMessage[];
  historyLatency: number;
  historyHasNext: boolean;
  historyCursor: string | null;
  searchRows: AdminMessage[];
  searchLatency: number;
  searchHasNext: boolean;
  searchCursor: string | null;
  lastExportJob: AdminExportJob | null;
  exportJobContext: ExportJobContext | null;
  notice: string;
  noticeError: boolean;
  setBaseUrl: (value: string) => void;
  setToken: (value: string) => void;
  setRoomId: (value: string) => void;
  setQuery: (value: string) => void;
  setSearchMode: (value: SearchMode) => void;
  setSenderId: (value: string) => void;
  setFrom: (value: string) => void;
  setTo: (value: string) => void;
  setLimit: (value: string) => void;
  setAdvancedOpen: (open: boolean) => void;
  persistState: (storage: Storage) => { baseUrl: string; roomId: string };
  showNotice: (message: string, isError?: boolean) => void;
  setStatus: (status: AdminRoomStatus | null) => void;
  setHistoryPage: (page: AdminMessagePage) => void;
  setSearchPage: (page: AdminMessagePage) => void;
  setLastExportJob: (job: AdminExportJob | null) => void;
  setExportJobContext: (context: ExportJobContext | null) => void;
}

const loadedState = initialAdminState();

export const useAdminStore = create<AdminStore>((set, get) => ({
  baseUrl: loadedState.baseUrl,
  token: loadedState.token,
  roomId: loadedState.roomId,
  query: '',
  searchMode: loadedState.searchMode,
  senderId: '',
  from: '',
  to: '',
  limit: '50',
  advancedOpen: false,
  status: null,
  historyRows: [],
  historyLatency: 0,
  historyHasNext: false,
  historyCursor: null,
  searchRows: [],
  searchLatency: 0,
  searchHasNext: false,
  searchCursor: null,
  lastExportJob: null,
  exportJobContext: null,
  notice: 'Ready',
  noticeError: false,
  setBaseUrl: (value) => set({ baseUrl: value }),
  setToken: (value) => set({ token: value }),
  setRoomId: (value) => set({ roomId: value }),
  setQuery: (value) => set({ query: value }),
  setSearchMode: (value) => set({ searchMode: value }),
  setSenderId: (value) => set({ senderId: value }),
  setFrom: (value) => set({ from: value }),
  setTo: (value) => set({ to: value }),
  setLimit: (value) => set({ limit: value }),
  setAdvancedOpen: (open) => set({ advancedOpen: open }),
  persistState: (storage) => {
    const state = get();
    const normalizedBaseUrl = state.baseUrl.trim() || '/api';
    const normalizedRoomId = state.roomId.trim() || '1';
    set({
      baseUrl: normalizedBaseUrl,
      roomId: normalizedRoomId,
    });
    try {
      saveAdminState(storage, {
        baseUrl: normalizedBaseUrl,
        token: state.token,
        roomId: normalizedRoomId,
        searchMode: state.searchMode,
        historyCursor: null,
        searchCursor: null,
      });
    } catch {
      // storage 비활성 환경은 정규화된 메모리 상태만 유지한다.
    }
    return { baseUrl: normalizedBaseUrl, roomId: normalizedRoomId };
  },
  showNotice: (message, isError = false) => {
    set({ notice: message, noticeError: isError });
  },
  setStatus: (status) => set({ status }),
  setHistoryPage: (page) => {
    set({
      historyRows: page.messages,
      historyCursor: page.nextCursor,
      historyLatency: page.latencyMs,
      historyHasNext: page.hasNext,
    });
  },
  setSearchPage: (page) => {
    set({
      searchRows: page.messages,
      searchCursor: page.nextCursor,
      searchLatency: page.latencyMs,
      searchHasNext: page.hasNext,
    });
  },
  setLastExportJob: (job) => set({ lastExportJob: job }),
  setExportJobContext: (context) => set({ exportJobContext: context }),
}));
