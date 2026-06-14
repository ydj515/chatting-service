import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const openapi = readFileSync(new URL('../../docs/openapi.yaml', import.meta.url), 'utf8');
const migrationPolicy = readFileSync(
  new URL('../../docs/public_history_cursor_migration.md', import.meta.url),
  'utf8',
);

test('public room history documents opaque cursor token compatibility', () => {
  const path = openapi.slice(
    openapi.indexOf('  /chat-rooms/{id}/messages/cursor:'),
    openapi.indexOf('  /chat-rooms/{id}/messages/gap:'),
  );
  const response = openapi.slice(
    openapi.indexOf('    MessagePageResponse:'),
    openapi.indexOf('    SendMessageRequest:'),
  );

  assert.match(path, /name: cursor[\s\S]*Deprecated numeric roomSeq cursor/);
  assert.match(path, /name: cursorToken[\s\S]*opaque[\s\S]*type: string/);
  assert.match(response, /nextCursor:[\s\S]*deprecated: true/);
  assert.match(response, /nextCursorToken:[\s\S]*opaque[\s\S]*type: string/);
  assert.match(response, /prevCursor:[\s\S]*deprecated: true/);
  assert.match(response, /prevCursorToken:[\s\S]*opaque[\s\S]*type: string/);
});

test('public numeric cursor deprecation policy defines migration window and precedence', () => {
  assert.match(migrationPolicy, /2 releases or 30 days after public client migration/);
  assert.match(migrationPolicy, /cursorToken wins over numeric cursor/);
  assert.match(migrationPolicy, /legacy numeric cursor remains accepted/);
  assert.match(migrationPolicy, /Rollback/);
});
