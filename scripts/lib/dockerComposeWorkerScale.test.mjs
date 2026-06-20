import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8');

function serviceBlock(serviceName) {
  const start = compose.indexOf(`  ${serviceName}:`);
  assert.notEqual(start, -1, `${serviceName} service must exist`);

  const nextService = compose.slice(start + 1).match(/\n  [a-zA-Z0-9_-]+:\n/);
  if (!nextService) {
    return compose.slice(start);
  }
  return compose.slice(start, start + 1 + nextService.index);
}

test('chat-worker-app-1 can be scaled by docker compose for multi fanout worker tests', () => {
  const worker = serviceBlock('chat-worker-app-1');

  assert.doesNotMatch(worker, /\n    container_name:/);
  assert.doesNotMatch(worker, /\n    hostname:/);
  assert.match(worker, /WORKER_ROLES: \${WORKER_ROLES:-message-writer,fanout,admin-export,room-policy}/);
  assert.match(worker, /CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED: \${CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED:-true}/);
});
