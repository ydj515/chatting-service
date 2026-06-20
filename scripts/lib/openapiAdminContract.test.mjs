import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const openapi = readFileSync(new URL('../../docs/openapi.yaml', import.meta.url), 'utf8');

test('AdminExportMessagesRequest documents roomId or query requirement', () => {
  const schema = openapi.slice(
    openapi.indexOf('    AdminExportMessagesRequest:'),
    openapi.indexOf('    AdminExportJobDto:'),
  );

  assert.match(schema, /anyOf:/);
  assert.match(schema, /required: \[roomId\]/);
  assert.match(schema, /required: \[query\]/);
});

test('admin search cursor is documented as an opaque string', () => {
  const searchPath = openapi.slice(
    openapi.indexOf('  /admin/messages/search:'),
    openapi.indexOf('    get:', openapi.indexOf('  /admin/rooms/{roomId}/status:')),
  );
  const searchResponse = openapi.slice(
    openapi.indexOf('    AdminMessageSearchResponse:'),
    openapi.indexOf('    AdminRoomStatusDto:'),
  );

  assert.match(searchPath, /name: cursor[\s\S]*opaque[\s\S]*type: string/);
  assert.match(searchResponse, /nextCursor:[\s\S]*type: string[\s\S]*nullable: true/);
});

test('admin room history cursor is documented as an opaque string', () => {
  const historyPath = openapi.slice(
    openapi.indexOf('  /admin/chat-rooms/{roomId}/messages:'),
    openapi.indexOf('  /admin/messages/search:'),
  );
  const historyResponse = openapi.slice(
    openapi.indexOf('    AdminMessagePageResponse:'),
    openapi.indexOf('    AdminMessageSearchResponse:'),
  );

  assert.match(historyPath, /name: cursor[\s\S]*opaque[\s\S]*type: string/);
  assert.match(historyResponse, /nextCursor:[\s\S]*type: string[\s\S]*nullable: true/);
});

test('admin room policy documents automatic policy guard and moderator priority', () => {
  const statusSchema = openapi.slice(
    openapi.indexOf('    AdminRoomStatusDto:'),
    openapi.indexOf('    AdminRoomPolicyUpdateRequest:'),
  );
  const requestSchema = openapi.slice(
    openapi.indexOf('    AdminRoomPolicyUpdateRequest:'),
    openapi.indexOf('    AdminExportMessagesRequest:'),
  );

  assert.match(statusSchema, /autoPolicyEnabled:[\s\S]*type: boolean/);
  assert.match(statusSchema, /moderatorPriority:[\s\S]*type: boolean/);
  assert.match(requestSchema, /autoPolicyEnabled:[\s\S]*type: boolean/);
  assert.match(requestSchema, /moderatorPriority:[\s\S]*type: boolean/);
  assert.match(requestSchema, /clearRateLimit:[\s\S]*type: boolean/);
  assert.match(requestSchema, /clearUserRateLimit:[\s\S]*type: boolean/);
  assert.match(requestSchema, /clearSlowMode:[\s\S]*type: boolean/);
});
