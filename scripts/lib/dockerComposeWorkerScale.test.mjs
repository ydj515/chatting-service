import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8');
const envExample = readFileSync(new URL('../../.env.example', import.meta.url), 'utf8');
const nginxMainConfig = readFileSync(new URL('../../infra/nginx/nginx.main.conf.template', import.meta.url), 'utf8');

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
  assert.match(worker, /WORKER_ROLES: \${WORKER_ROLES:-message-writer,fanout,admin-export,room-policy,room-seq-gap-audit}/);
  assert.match(worker, /CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED: \${CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED:-true}/);
});

test('default worker roles include roomSeq gap audit for cluster readiness', () => {
  assert.match(
    compose,
    /WORKER_ROLES: \${WORKER_ROLES:-message-writer,fanout,admin-export,room-policy,room-seq-gap-audit}/,
  );
});

test('compose passes roomSeq gap audit settings to worker containers', () => {
  const worker = serviceBlock('chat-worker-app-1');

  assert.match(
    worker,
    /CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_ENABLED: \${CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_ENABLED:-true}/,
  );
  assert.match(
    worker,
    /CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_POLL_DELAY_MILLIS: \${CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_POLL_DELAY_MILLIS:-60000}/,
  );
  assert.match(
    worker,
    /CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_LOOKBACK: \${CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_LOOKBACK:-5m}/,
  );
});

test('compose passes websocket heartbeat settings to app containers', () => {
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_ENABLED: \${CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_ENABLED:-true}/,
  );
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_INTERVAL_MILLIS: \${CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_INTERVAL_MILLIS:-30000}/,
  );
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_SCHEDULER_POLL_INTERVAL_MILLIS: \${CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_SCHEDULER_POLL_INTERVAL_MILLIS:-10000}/,
  );
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_TIMEOUT_MILLIS: \${CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_TIMEOUT_MILLIS:-90000}/,
  );
});

test('compose passes websocket outbound budget settings to app containers', () => {
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_OUTBOUND_QUEUE_MAX_PENDING_MESSAGES: \${CHAT_WEBSOCKET_GATEWAY_OUTBOUND_QUEUE_MAX_PENDING_MESSAGES:-1024}/,
  );
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_OUTBOUND_EXECUTOR_THREADS: \${CHAT_WEBSOCKET_GATEWAY_OUTBOUND_EXECUTOR_THREADS:-128}/,
  );
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_TIME_LIMIT_MILLIS: \${CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_TIME_LIMIT_MILLIS:-30000}/,
  );
  assert.match(
    compose,
    /CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_BUFFER_SIZE_LIMIT_BYTES: \${CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_BUFFER_SIZE_LIMIT_BYTES:-4194304}/,
  );
  assert.match(envExample, /^CHAT_WEBSOCKET_GATEWAY_OUTBOUND_QUEUE_MAX_PENDING_MESSAGES=1024$/m);
  assert.match(envExample, /^CHAT_WEBSOCKET_GATEWAY_OUTBOUND_EXECUTOR_THREADS=128$/m);
  assert.match(envExample, /^CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_TIME_LIMIT_MILLIS=30000$/m);
  assert.match(envExample, /^CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_BUFFER_SIZE_LIMIT_BYTES=4194304$/m);
});

test('nginx main config exposes worker connection budget through envsubst', () => {
  assert.match(nginxMainConfig, /worker_processes \$\{NGINX_WORKER_PROCESSES\};/);
  assert.match(nginxMainConfig, /worker_connections \$\{NGINX_WORKER_CONNECTIONS\};/);
  assert.match(nginxMainConfig, /include \/etc\/nginx\/conf\.d\/\*\.conf;/);
});

test('compose gives nginx nofile and worker connection budget for staged gate', () => {
  const nginx = serviceBlock('nginx');

  assert.match(nginx, /NGINX_WORKER_PROCESSES: \$\{NGINX_WORKER_PROCESSES:-auto\}/);
  assert.match(nginx, /NGINX_WORKER_CONNECTIONS: \$\{NGINX_WORKER_CONNECTIONS:-20000\}/);
  assert.match(
    nginx,
    /\.\/infra\/nginx\/nginx\.main\.conf\.template:\/etc\/nginx\/nginx\.conf\.template:ro/,
  );
  assert.match(nginx, /ulimits:/);
  assert.match(nginx, /nofile:/);
  assert.match(nginx, /soft: \$\{NGINX_NOFILE_SOFT:-65535\}/);
  assert.match(nginx, /hard: \$\{NGINX_NOFILE_HARD:-65535\}/);
});

test('.env.example does not override cluster defaults with stale worker or Redis profiles', () => {
  assert.match(envExample, /^SPRING_PROFILES_ACTIVE=docker,redis-cluster$/m);
  assert.match(envExample, /^WORKER_ROLES=message-writer,fanout,admin-export,room-policy,room-seq-gap-audit$/m);
});
