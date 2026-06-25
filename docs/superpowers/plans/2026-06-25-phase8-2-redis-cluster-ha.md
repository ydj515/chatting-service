# Phase 8.2 Redis Cluster HA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the full Docker Compose backend from a single Redis instance to a 3-master/3-replica Redis Cluster while keeping standalone Redis available for host-based local development.

**Architecture:** Keep `redis` as a dev-profile standalone service for `mise run dev:*`, and make the full Docker backend run under the Compose `cluster` profile with `redis-cluster-node-1..6` plus a one-shot `redis-cluster-init` service. Spring Boot uses the existing standalone Redis settings in the `docker` profile, and enables Lettuce cluster mode only when the additional `redis-cluster` profile is active.

**Tech Stack:** Docker Compose, Redis 7.2 Cluster, Spring Boot 3.3 Redis auto-configuration with Lettuce, Node.js `node:test`, Markdown runbooks.
---

## Tasks

### Task 1: Redis Cluster Contract Tests

**Files:**

- Create: `scripts/lib/phase8RedisClusterCompose.test.mjs`

- [x] **Step 1: Write failing artifact contract tests**

```javascript
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
```

- [x] **Step 2: Run red test**

```bash
node --test scripts/lib/phase8RedisClusterCompose.test.mjs
```

Expected before implementation: FAIL because `application-redis-cluster.yml`, `infra/redis/redis-cluster.conf`, and `infra/redis/create-cluster.sh` do not exist yet.

### Task 2: Redis Cluster Compose and Spring Profile

**Files:**

- Modify: `docker-compose.yml`
- Create: `infra/redis/redis-cluster.conf`
- Create: `infra/redis/create-cluster.sh`
- Create: `chat-runtime-config/src/main/resources/application-redis-cluster.yml`

- [x] **Step 1: Add Redis Cluster profile resources**

Add `application-redis-cluster.yml` with `spring.data.redis.cluster.nodes` and `max-redirects`.

- [x] **Step 2: Replace full-backend Redis dependency with cluster init**

Make `chat-api-app-*`, `chat-websocket-app-*`, `chat-worker-app-1`, and `chat-admin-app-1` depend on `redis-cluster-init`, and provide `REDIS_CLUSTER_NODES` through the shared app environment.

- [x] **Step 3: Keep standalone Redis for host local development**

Keep the existing `redis` service under `profiles: [ "dev" ]`, so `mise run start:infra` can explicitly start it for host Gradle apps.

- [x] **Step 4: Run green contract test**

```bash
node --test scripts/lib/phase8RedisClusterCompose.test.mjs
```

Expected: PASS.

### Task 3: Runbook and Configuration Docs

**Files:**

- Modify: `docs/infrastructure.md`
- Modify: `docs/configuration.md`
- Modify: `docs/redis_cluster_key_naming.md`
- Modify: `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`
- Modify: `start-cluster.sh`

- [x] **Step 1: Document the two Redis topologies**

Document standalone Redis for host local dev and Redis Cluster HA for full Docker backend.

- [x] **Step 2: Document data-loss caveat**

State that `appendfsync everysec` can lose up to one second of acknowledged Redis ingest on node failure, and that Phase 8.7 gap audit is the detection path.

- [x] **Step 3: Update startup runbook**

Update `start-cluster.sh` to start cluster services and print Redis Cluster monitoring commands.

### Task 4: Verification

**Files:**

- All modified files

- [x] **Step 1: Run focused artifact tests**

```bash
node --test scripts/lib/phase8RedisClusterCompose.test.mjs
node --check scripts/lib/phase8RedisClusterCompose.test.mjs
```

Expected: PASS.

- [x] **Step 2: Validate Docker Compose**

```bash
mise run verify:compose
```

Expected: PASS.

- [x] **Step 3: Run backend tests**

```bash
./gradlew test --no-daemon
```

Expected: PASS.

- [x] **Step 4: Run diff check**

```bash
git diff --check
```

Expected: no output.
