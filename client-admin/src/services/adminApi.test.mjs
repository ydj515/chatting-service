import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildAdminHistoryUrl,
  buildAdminSearchUrl,
  createAdminHeaders,
} from './adminApi.mjs';

test('admin search URL includes query filters and bounded limit', () => {
  const url = buildAdminSearchUrl('/api', {
    query: 'hello world',
    roomId: 10,
    senderId: 7,
    limit: 500,
  });

  assert.equal(url, '/api/admin/messages/search?q=hello+world&roomId=10&senderId=7&limit=100');
});

test('admin history URL includes room time range cursor and positive limit', () => {
  const url = buildAdminHistoryUrl('/api', 10, {
    from: '2026-06-14T00:00:00',
    to: '2026-06-15T00:00:00',
    cursor: 123,
    limit: -1,
  });

  assert.equal(
    url,
    '/api/admin/chat-rooms/10/messages?from=2026-06-14T00%3A00%3A00&to=2026-06-15T00%3A00%3A00&cursor=123&limit=1',
  );
});

test('admin headers include X-Admin-Token', () => {
  assert.deepEqual(createAdminHeaders('secret-token'), {
    'Content-Type': 'application/json',
    'X-Admin-Token': 'secret-token',
  });
});
