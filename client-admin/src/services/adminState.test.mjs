import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadAdminState, saveAdminState } from './adminState.mjs';

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem(key) {
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) {
      values.set(key, String(value));
    },
    has(key) {
      return values.has(key);
    },
    value(key) {
      return values.get(key);
    },
  };
}

test('loadAdminState does not restore admin token from persistent storage', () => {
  const storage = memoryStorage({
    client_admin_base_url: '/api',
    client_admin_token: 'persisted-token',
    client_admin_room_id: '10',
    client_admin_search_mode: 'CONTAINS',
  });

  const state = loadAdminState(storage);

  assert.equal(state.baseUrl, '/api');
  assert.equal(state.token, '');
  assert.equal(state.roomId, '10');
  assert.equal(state.searchMode, 'CONTAINS');
});

test('saveAdminState does not write admin token to persistent storage', () => {
  const storage = memoryStorage();

  saveAdminState(storage, {
    baseUrl: '/admin-api',
    token: 'secret-token',
    roomId: '20',
    searchMode: 'FTS',
  });

  assert.equal(storage.value('client_admin_base_url'), '/admin-api');
  assert.equal(storage.value('client_admin_room_id'), '20');
  assert.equal(storage.value('client_admin_search_mode'), 'FTS');
  assert.equal(storage.has('client_admin_token'), false);
});
