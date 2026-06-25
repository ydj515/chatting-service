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

test('default Docker backend uses six Redis Cluster nodes and a cluster init gate', () => {
  for (let index = 1; index <= 6; index += 1) {
    assert.match(compose, new RegExp(`redis-cluster-node-${index}:`));
    assert.match(compose, new RegExp(`redis_cluster_node_${index}_data:`));
  }

  assert.match(compose, /redis-cluster-init:/);
  assert.match(compose, /redis-cli --cluster create/);
  assert.match(compose, /redis-cluster-init: \{ condition: service_completed_successfully \}/);
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
  assert.match(redisClusterConfig, /appendonly yes/);
  assert.match(redisClusterConfig, /appendfsync everysec/);
});

test('cluster bootstrap script is idempotent and creates 3 masters with 3 replicas', () => {
  assert.match(createClusterScript, /cluster_state:ok/);
  assert.match(createClusterScript, /--cluster-replicas 1/);
  assert.match(createClusterScript, /redis-cluster-node-6:6379/);
});
