import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8');
const dockerConfig = readFileSync(
  new URL('../../chat-runtime-config/src/main/resources/application-docker.yml', import.meta.url),
  'utf8',
);
const clusterConfig = readFileSync(
  new URL('../../chat-runtime-config/src/main/resources/application-redis-cluster.yml', import.meta.url),
  'utf8',
);
const redisClusterConfig = readFileSync(
  new URL('../../infra/redis/redis-cluster.conf', import.meta.url),
  'utf8',
);
const createClusterScript = readFileSync(
  new URL('../../infra/redis/create-cluster.sh', import.meta.url),
  'utf8',
);
const redisConfig = readFileSync(
  new URL('../../chat-persistence/src/main/kotlin/config/RedisConfig.kt', import.meta.url),
  'utf8',
);

function serviceBlock(name) {
  const match = compose.match(
    new RegExp(`\\n  ${name}:\\n([\\s\\S]*?)(?=\\n  [a-zA-Z0-9_-]+:\\n|\\n# 볼륨 정의|\\nvolumes:)`),
  );
  assert.ok(match, `service ${name} should exist`);
  return match[1];
}

test('cluster profile backend uses six Redis Cluster nodes and an init gate', () => {
  for (let index = 1; index <= 6; index += 1) {
    const block = serviceBlock(`redis-cluster-node-${index}`);
    assert.match(block, /profiles: \[ "cluster" \]/);
    assert.match(block, /127\.0\.0\.1:\$\{REDIS_CLUSTER_NODE_\d_HOST_PORT:-\d+\}:6379/);
    assert.match(compose, new RegExp(`redis_cluster_node_${index}_data:`));
  }

  const initBlock = serviceBlock('redis-cluster-init');
  assert.match(initBlock, /profiles: \[ "cluster" \]/);
  assert.match(initBlock, /REDIS_CLUSTER_NODES:/);
  assert.match(initBlock, /REDIS_CLUSTER_BOOTSTRAP_TIMEOUT_SECONDS:/);
  assert.match(createClusterScript, /redis-cli --cluster create/);
});

test('every app service waits for redis-cluster-init gate', () => {
  for (const service of [
    'chat-api-app-1',
    'chat-api-app-2',
    'chat-websocket-app-1',
    'chat-websocket-app-2',
    'chat-worker-app-1',
    'chat-admin-app-1',
  ]) {
    const block = serviceBlock(service);
    assert.match(block, /profiles: \[ "cluster" \]/);
    assert.match(block, /redis-cluster-init: \{ condition: service_completed_successfully \}/);
  }
});

test('standalone Redis is isolated to the dev profile', () => {
  assert.match(compose, /redis:\n(?:    .*\n)*?    profiles: \[ "dev" \]/);
});

test('Docker app profile activates Lettuce cluster mode without changing local docker profile', () => {
  assert.match(compose, /SPRING_PROFILES_ACTIVE: \$\{SPRING_PROFILES_ACTIVE:-docker,redis-cluster\}/);
  assert.match(compose, /REDIS_CLUSTER_NODES:/);
  assert.doesNotMatch(dockerConfig, /cluster:\n\s+nodes:/);
  assert.match(clusterConfig, /spring:\n(?:  .*\n)*  data:\n(?:    .*\n)*    redis:\n(?:      .*\n)*      cluster:/);
  assert.match(clusterConfig, /nodes: \$\{REDIS_CLUSTER_NODES:/);
});

test('Redis Cluster config enables cluster mode and keeps everysec append fsync policy', () => {
  assert.match(redisClusterConfig, /cluster-enabled yes/);
  assert.match(redisClusterConfig, /cluster-require-full-coverage yes/);
  assert.match(redisClusterConfig, /cluster-preferred-endpoint-type hostname/);
  assert.match(redisClusterConfig, /appendonly yes/);
  assert.match(redisClusterConfig, /appendfsync everysec/);
});

test('cluster bootstrap script is idempotent and creates 3 masters with 3 replicas', () => {
  assert.match(createClusterScript, /REDIS_CLUSTER_NODES/);
  assert.match(createClusterScript, /REDIS_CLUSTER_BOOTSTRAP_TIMEOUT_SECONDS/);
  assert.match(createClusterScript, /Timed out waiting for Redis Cluster node/);
  assert.match(createClusterScript, /Timed out waiting for Redis Cluster state ok/);
  assert.match(createClusterScript, /cluster_state:ok/);
  assert.match(createClusterScript, /--cluster-replicas 1/);
  assert.match(createClusterScript, /redis-cluster-node-6:6379/);
});

test('Lettuce cluster topology refresh is enabled for the redis-cluster profile', () => {
  assert.match(redisConfig, /@Profile\("redis-cluster"\)/);
  assert.match(redisConfig, /LettuceClientConfigurationBuilderCustomizer/);
  assert.match(redisConfig, /ClusterTopologyRefreshOptions/);
  assert.match(redisConfig, /enableAllAdaptiveRefreshTriggers/);
  assert.match(redisConfig, /enablePeriodicRefresh\(Duration\.ofSeconds\(30\)\)/);
});
