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
