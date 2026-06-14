import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const files = [
  '../../chat-admin/src/main/kotlin/com/chat/admin/config/AdminProperties.kt',
  '../../docker-compose.yml',
  '../../chat-runtime-config/src/main/resources/application-docker.yml',
  '../measure-admin-search-p95.mjs',
];

test('admin token has no predictable runtime default', () => {
  for (const relativePath of files) {
    const text = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
    assert.doesNotMatch(text, /local-admin-token/);
    assert.doesNotMatch(text, /CHAT_ADMIN_TOKEN[:-]local-admin-token/);
  }
});
