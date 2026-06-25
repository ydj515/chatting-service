import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildAdminExportStatusUrl,
  buildAdminHistoryUrl,
  buildAdminSearchUrl,
  createAdminHeaders,
} from './adminApi.ts';

test('admin search URL includes query filters and bounded limit', () => {
  const url = buildAdminSearchUrl('/api', {
    query: 'hello world',
    mode: 'CONTAINS',
    roomId: 10,
    senderId: 7,
    cursor: 'opaque.cursor/with+symbols',
    limit: 500,
  });

  assert.equal(url, '/api/admin/messages/search?q=hello+world&mode=CONTAINS&roomId=10&senderId=7&cursor=opaque.cursor%2Fwith%2Bsymbols&limit=100');
});

test('admin history URL includes room time range cursor and positive limit', () => {
  const url = buildAdminHistoryUrl('/api', 10, {
    from: '2026-06-14T00:00:00',
    to: '2026-06-15T00:00:00',
    cursor: 'opaque.history/with+symbols',
    limit: -1,
  });

  assert.equal(
    url,
    '/api/admin/chat-rooms/10/messages?from=2026-06-14T00%3A00%3A00&to=2026-06-15T00%3A00%3A00&cursor=opaque.history%2Fwith%2Bsymbols&limit=1',
  );
});

test('admin headers include X-Admin-Token', () => {
  assert.deepEqual(createAdminHeaders('secret-token'), {
    'Content-Type': 'application/json',
    'X-Admin-Token': 'secret-token',
  });
});

test('admin export status URL encodes job id', () => {
  const url = buildAdminExportStatusUrl('/api/', 'export/with symbols');

  assert.equal(url, '/api/admin/exports/export%2Fwith%20symbols');
});
