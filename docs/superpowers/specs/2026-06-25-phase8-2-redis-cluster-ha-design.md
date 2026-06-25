# Phase 8.2 Redis Cluster HA 설계서

- 작성일: 2026-06-25
- 슬라이스: Phase 8.2 Redis Cluster HA
- 상태: 구현 완료
- 구현 브랜치: `feat/phase8-2-redis-cluster-ha`
- 계획 문서: `docs/superpowers/plans/2026-06-25-phase8-2-redis-cluster-ha.md`

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 8 목표는 Kubernetes 전환 전 Docker Compose 기준 공개 트래픽 운영 토대를 확보하는 것이다.
- 기존 Redis는 단일 `redis:7.2-alpine` 인스턴스라 node 장애 시 sequence 발급, Redis Streams ingest, admission, presence, fanout owner lease, pub/sub 경로가 함께 중단된다.
- 애플리케이션 Redis key naming은 이미 Redis Cluster를 고려해 단일 key command와 필요한 hash tag 정책을 정리했다.
- Message admission은 room/user/slow-mode multi-key Lua script를 쓰며, 같은 room hash tag로 같은 slot을 보장해야 한다.
- WebSocket ticket rate limit, fanout owner lease, sequence, stream append는 기본적으로 단일 key 또는 shard별 key 경로로 유지한다.
- Docker Desktop/host Gradle 개발 모드는 Redis Cluster redirect endpoint가 container hostname을 반환할 수 있어 standalone Redis를 유지할 필요가 있다.

### 목표

- 전체 Docker backend 기본 Redis topology를 3 master + 3 replica Redis Cluster로 전환한다.
- Redis Cluster bootstrap은 Compose에서 재현 가능하고 idempotent해야 한다.
- 앱 컨테이너는 Spring `redis-cluster` profile과 Lettuce cluster mode를 사용한다.
- host Gradle 개발 모드는 standalone Redis를 유지한다.
- `appendfsync everysec`로 인한 최대 1초 Redis ingest 유실 가능성을 문서화한다.
- Redis Cluster 구성 자체를 contract test와 Compose config 검증으로 보호한다.

### 비범위

- Redis Cluster failover chaos/e2e는 이 슬라이스의 필수 완료 조건으로 두지 않는다.
- Phase 8.7 gap audit/heartbeat 구현은 별도 슬라이스로 남긴다.
- Redis Operator, PersistentVolume, Kubernetes Service/Ingress 구성은 Phase 9로 넘긴다.
- Redis Streams shard count 동적 확장과 10k msg/sec release gate는 Phase 8.4 범위다.

## 2. 해결 접근

### 선택한 접근

Compose 기본 backend에는 `redis-cluster-node-1..6`을 추가하고, `redis-cluster-init` one-shot service가 `redis-cli --cluster create ... --cluster-replicas 1`을 실행한다. 앱 컨테이너는 `SPRING_PROFILES_ACTIVE=docker,redis-cluster`를 기본값으로 사용하고, `application-redis-cluster.yml`에서 `spring.data.redis.cluster.nodes`를 설정한다.

기존 `redis` service는 `profiles: ["dev"]`로 격리해 host Gradle 개발 모드에서만 사용한다.

### 이유

- 6노드 Redis Cluster는 최소 3 master + 3 replica HA 구성을 Compose에서 재현한다.
- one-shot init gate는 앱이 cluster 생성 전 Redis에 접근하는 race를 줄인다.
- Spring Boot/Lettuce cluster mode를 profile로 분리하면 full Docker와 host dev topology를 동시에 지원할 수 있다.
- standalone Redis를 dev profile로 유지하면 Docker Desktop hostname redirect 문제를 피하면서 기존 `mise run dev:*` 경험을 보존한다.

## 3. 설계 상세

### Redis Cluster services

- `redis-cluster-node-1..6`: 동일한 `infra/redis/redis-cluster.conf`를 사용한다.
- 각 node는 별도 named volume을 사용한다.
- 각 node는 `cluster-announce-hostname`을 자신의 Compose service name으로 설정한다.
- host port는 `6379`, `6380`, `6381`, `6382`, `6383`, `6384`로 노출한다.

### Cluster bootstrap

`infra/redis/create-cluster.sh`는 다음 순서로 동작한다.

1. 6개 node의 `PING` 준비를 기다린다.
2. node 1의 `cluster info`가 이미 `cluster_state:ok`이면 성공 종료한다.
3. `redis-cli --cluster create ... --cluster-replicas 1 --cluster-yes`를 실행한다.
4. `cluster_state:ok`가 될 때까지 대기한다.

### Spring profile

`application-redis-cluster.yml`은 다음 속성을 제공한다.

- `spring.data.redis.cluster.nodes`
- `spring.data.redis.cluster.max-redirects`

`application-docker.yml`에는 cluster nodes를 직접 넣지 않는다. 따라서 `docker` profile만 사용하는 host Gradle 앱은 standalone Redis에 연결한다.

### App startup dependency

`chat-api-app-*`, `chat-websocket-app-*`, `chat-worker-app-1`, `chat-admin-app-1`은 `redis-cluster-init: service_completed_successfully`에 의존한다. Redis Cluster seed node 목록은 shared app environment의 `REDIS_CLUSTER_NODES`로 전달한다.

### Persistence policy

Redis Cluster node는 다음 persistence 정책을 사용한다.

- `appendonly yes`
- `appendfsync everysec`
- RDB snapshot save policy 유지

이 설정은 처리량과 durability의 균형을 위한 선택이다. node 또는 host crash 시 마지막 fsync 이후 최대 1초의 Redis ingest는 손실될 수 있으며, Phase 8.7 gap audit/heartbeat에서 감지 경로를 제공한다.

## 4. 구현 결과

구현 완료 항목:

- `chat-runtime-config/src/main/resources/application-redis-cluster.yml` 추가
- `infra/redis/redis-cluster.conf` 추가
- `infra/redis/create-cluster.sh` 추가
- `docker-compose.yml`에 6개 Redis Cluster node와 init gate 추가
- full Docker app dependency를 `redis-cluster-init`으로 전환
- standalone `redis`를 dev profile로 격리
- `docs/infrastructure.md`, `docs/configuration.md`, `docs/redis_cluster_key_naming.md` runbook 보강
- `start-cluster.sh`를 Redis Cluster 기준으로 갱신
- `scripts/lib/phase8RedisClusterCompose.test.mjs` contract test 추가

검증된 항목:

- artifact contract test 통과
- Docker Compose config 검증 통과
- Gradle 전체 test 통과
- Node script 전체 test 통과
- `git diff --check` 통과
- 실제 Redis Cluster smoke에서 `cluster_state:ok`, `cluster_known_nodes:6`, `cluster_size:3`, `cluster_slots_assigned:16384` 확인

남은 운영 검증:

- 앱 전체 stack 기동 후 Redis Cluster를 통한 REST/WebSocket/worker e2e 확인
- Redis master kill 후 replica promotion과 앱 reconnect behavior 확인
- Lua script 경로(ticket/admission/fanout owner lease)가 실제 cluster에서 운영 부하를 견디는지 확인

## 5. 복잡도

- Redis single-key command 시간 복잡도: 평균 `O(1)`
- Redis single-key Lua script 시간 복잡도: 현재 script 기준 `O(1)`
- message admission multi-key Lua script 시간 복잡도: 같은 room slot 내 `O(1)`
- Cluster bootstrap 시간 복잡도: 고정 6노드 기준 `O(1)`, 일반화하면 `O(N)`
- Compose service 수 증가에 따른 관리 비용: 고정 6노드 기준 `O(1)`
- Redis 저장 공간 복잡도: `O(activeKeys + streamRetention)`, replica 포함 시 master 저장량에 replica factor가 곱해진다.

## 6. 주의사항

> - Redis Cluster에서 multi-key Lua script는 모든 key가 같은 slot이어야 한다.
> - message admission key는 room hash tag로 같은 slot을 보장하지만, very hot room에서는 해당 slot이 병목이 될 수 있다.
> - host Gradle 앱은 기본적으로 standalone Redis를 사용한다. cluster profile을 host에서 켜면 redirect endpoint 접근 문제가 생길 수 있다.
> - `appendfsync everysec`는 최대 1초 Redis ingest 유실 가능성을 남긴다. 운영상 gap audit/heartbeat가 필요하다.
> - Compose smoke가 Redis Cluster 생성 성공을 보장해도 app e2e와 failover recovery를 대체하지는 않는다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| full Docker 기본 Redis Cluster + dev standalone 유지 | 운영 HA 토대와 local dev 편의를 함께 만족한다 | Redis topology가 두 개라 문서와 설정 분기가 필요하다 | 선택 |
| standalone Redis 완전 제거 | 운영 전제는 단순하다 | host Gradle dev가 Redis Cluster redirect 문제에 취약하다 | 제외 |
| Redis Sentinel 구성 | 단일 primary failover 모델이 단순하다 | Redis Cluster slot/shard 전제를 검증하지 못한다 | 제외 |
| Kubernetes Redis Operator 선도입 | 운영 환경에 가깝다 | Phase 9 범위를 앞당겨 Compose 기준 Phase 8 목표를 흐린다 | 보류 |

## 8. 후속 질문

- Redis master kill failover smoke를 Phase 8.2 후속 gate로 추가할 것인가?
- admission Lua script를 실제 Redis Cluster에서 실행하는 integration test를 추가할 것인가?
- Phase 8.7 gap audit에서 Redis Streams gap과 PostgreSQL canonical store gap을 어떤 기준으로 비교할 것인가?
