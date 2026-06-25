import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const openapi = readFileSync(new URL('../../docs/openapi.yaml', import.meta.url), 'utf8');

function schemaBlock(name) {
  const start = openapi.indexOf(`    ${name}:`);
  assert.notEqual(start, -1, `${name} schema must exist`);
  const rest = openapi.slice(start + 1);
  const next = rest.search(/\n    [A-Za-z0-9]+:/);
  return next === -1 ? openapi.slice(start) : openapi.slice(start, start + 1 + next);
}

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

test('admin export status endpoint documents stable object uri and presigned download url', () => {
  const statusPath = openapi.slice(
    openapi.indexOf('  /admin/exports/{jobId}:'),
    openapi.indexOf('components:'),
  );
  const statusSchema = schemaBlock('AdminExportJobStatusDto');

  assert.match(statusPath, /get:/);
  assert.match(statusPath, /name: jobId[\s\S]*in: path[\s\S]*required: true/);
  assert.match(statusPath, /AdminExportJobStatusDto/);
  assert.match(statusSchema, /outputUri:[\s\S]*s3:\/\/chat-archives\/admin-exports\/export-1\.csv/);
  assert.match(statusSchema, /downloadUrl:[\s\S]*nullable: true/);
  assert.match(statusSchema, /downloadUrlExpiresAt:[\s\S]*format: date-time[\s\S]*nullable: true/);
});
