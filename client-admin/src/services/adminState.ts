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

const SEARCH_MODES: readonly SearchMode[] = ['FTS', 'CONTAINS'];

const toSearchMode = (value: string | null): SearchMode =>
  SEARCH_MODES.includes(value as SearchMode) ? (value as SearchMode) : DEFAULT_STATE.searchMode;

// 토큰은 보안상 절대 영속 저장하지 않는다(매 세션 재입력).
// Safari 프라이빗 모드 등 storage 접근이 차단된 환경에서도 앱이 크래시되지 않도록 try-catch로 보호한다.
export function loadAdminState(
  storage: AdminStorage,
  defaultBaseUrl: string = DEFAULT_STATE.baseUrl,
): AdminState {
  try {
    return {
      baseUrl: storage.getItem(STORAGE_KEYS.baseUrl) || defaultBaseUrl,
      token: DEFAULT_STATE.token,
      roomId: storage.getItem(STORAGE_KEYS.roomId) || DEFAULT_STATE.roomId,
      searchMode: toSearchMode(storage.getItem(STORAGE_KEYS.searchMode)),
      historyCursor: null,
      searchCursor: null,
    };
  } catch {
    return {
      baseUrl: defaultBaseUrl,
      token: DEFAULT_STATE.token,
      roomId: DEFAULT_STATE.roomId,
      searchMode: DEFAULT_STATE.searchMode,
      historyCursor: null,
      searchCursor: null,
    };
  }
}

export function saveAdminState(storage: AdminStorage, state: AdminState): void {
  try {
    storage.setItem(STORAGE_KEYS.baseUrl, state.baseUrl);
    storage.setItem(STORAGE_KEYS.roomId, state.roomId);
    storage.setItem(STORAGE_KEYS.searchMode, state.searchMode);
  } catch {
    // storage 비활성 환경은 무시하여 앱 크래시 방지
  }
}
