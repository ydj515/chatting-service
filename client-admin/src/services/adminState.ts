import type { AdminState, SearchMode } from '../types/index';

// localStorage.getItem/setItem만 사용하므로 테스트에서 in-memory 스텁으로 대체 가능
export interface AdminStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const STORAGE_KEYS = {
  baseUrl: 'client_admin_base_url',
  roomId: 'client_admin_room_id',
  searchMode: 'client_admin_search_mode',
} as const;

const DEFAULT_STATE = {
  baseUrl: '/api',
  token: '',
  roomId: '1',
  searchMode: 'FTS' as SearchMode,
};

// 토큰은 보안상 절대 영속 저장하지 않는다(매 세션 재입력).
export function loadAdminState(
  storage: AdminStorage,
  defaultBaseUrl: string = DEFAULT_STATE.baseUrl,
): AdminState {
  return {
    baseUrl: storage.getItem(STORAGE_KEYS.baseUrl) || defaultBaseUrl,
    token: DEFAULT_STATE.token,
    roomId: storage.getItem(STORAGE_KEYS.roomId) || DEFAULT_STATE.roomId,
    searchMode: (storage.getItem(STORAGE_KEYS.searchMode) as SearchMode) || DEFAULT_STATE.searchMode,
    historyCursor: null,
    searchCursor: null,
  };
}

export function saveAdminState(storage: AdminStorage, state: AdminState): void {
  storage.setItem(STORAGE_KEYS.baseUrl, state.baseUrl);
  storage.setItem(STORAGE_KEYS.roomId, state.roomId);
  storage.setItem(STORAGE_KEYS.searchMode, state.searchMode);
}
