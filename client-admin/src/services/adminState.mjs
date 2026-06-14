const DEFAULT_STATE = {
  baseUrl: '/api',
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
