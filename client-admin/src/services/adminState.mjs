// 빌드 타임에 index.html로 주입된 기본 base-url(예: dev 모드의 http://localhost/api).
// 주입값이 없으면 상대경로 '/api'로 폴백한다.
const INJECTED_BASE_URL =
  (typeof globalThis !== 'undefined' && globalThis.__ADMIN_DEFAULT_BASE_URL__) || '';

const DEFAULT_STATE = {
  baseUrl: INJECTED_BASE_URL || '/api',
  token: '',
  roomId: '1',
  searchMode: 'FTS',
};

export function loadAdminState(storage) {
  return {
    baseUrl: storage.getItem('client_admin_base_url') || DEFAULT_STATE.baseUrl,
    token: DEFAULT_STATE.token,
    roomId: storage.getItem('client_admin_room_id') || DEFAULT_STATE.roomId,
    searchMode: storage.getItem('client_admin_search_mode') || DEFAULT_STATE.searchMode,
    historyCursor: null,
    searchCursor: null,
  };
}

export function saveAdminState(storage, state) {
  storage.setItem('client_admin_base_url', state.baseUrl);
  storage.setItem('client_admin_room_id', state.roomId);
  storage.setItem('client_admin_search_mode', state.searchMode);
}
