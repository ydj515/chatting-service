# 인프라 가이드

## mise 태스크

```bash
mise run setup                     # 도구(JDK 21 등) 설치 및 클라이언트 의존성 설치
mise run                           # (기본) 로컬 개발: 인프라(도커)+백엔드(gradle)+클라이언트 = mise run dev
mise run dev:api                   # 단일 앱만 기동(인프라 자동). websocket/admin/worker 동일
mise run start:infra               # PostgreSQL primary/read-replica, archive worker, dev profile standalone Redis만 기동
mise run start                     # 전체 도커 클러스터(이미지 빌드+nginx+멀티 인스턴스)+클라이언트 기동
mise run build                     # 백엔드와 클라이언트 산출물 빌드
mise run clean:infra               # 인프라 볼륨 정리 (깨끗하게 재시작할 때)
mise run verify:postgres-replica   # PostgreSQL replica/archive 구성 검증
mise run verify:chat               # REST/WebSocket 채팅 흐름 검증
mise run stop                      # 전체 클러스터 종료
```

### 두 가지 실행 모드

- **로컬 개발(기본)**: 인프라와 nginx 게이트웨이(`:80`)만 Docker, 백엔드는 호스트 Gradle `bootRun`. 이미지 빌드가 없어 코드 변경이 바로 반영된다. dev용 nginx는 `nginx.dev.conf`로 upstream을 `host.docker.internal:8080~8082`(호스트 백엔드)로 라우팅한다. 멀티 인스턴스는 포함하지 않는다. 이 모드는 호스트 Gradle 앱이 Redis Cluster redirect를 처리하기 어렵지 않도록 dev profile의 standalone Redis를 사용한다.

  ```bash
  mise run            # = mise run dev
                      #   nginx 게이트웨이 http://localhost/api
                      #   호스트 백엔드 api:8080 / websocket:8081 / admin:8082 / worker:8083
  ```

- **전체 도커 클러스터**: 이미지를 빌드해 nginx 게이트웨이, 다중 앱 인스턴스, 3 master + 3 replica Redis Cluster를 포함한 전체 클러스터를 기동한다. 통합/최종 검증용이며 Compose `cluster` profile을 사용한다.

  ```bash
  mise run clean:infra   # (선택) 볼륨 정리
  mise run start         # 이미지 빌드 + 전체 기동 (http://localhost 게이트웨이)
  ```

---

## PostgreSQL Replica / Archive

### 구성 검증

```bash
docker compose up -d postgres postgres-primary-setup postgres-replica minio minio-init postgres-partition-archive

# replication 상태 확인
docker compose exec -T postgres \
  psql -U chatuser -d chatdb \
  -Atc "select usename,state,sync_state from pg_stat_replication;"

# replica recovery 상태 확인
docker compose exec -T postgres-replica \
  psql -U chatuser -d chatdb \
  -Atc "select pg_is_in_recovery(), to_regclass('public.chat_messages') is not null;"

# archive worker 수동 실행
docker compose run --rm \
  -e CHAT_PARTITION_ARCHIVE_RUN_ONCE=true \
  postgres-partition-archive
```

### 기대 결과

- replication 상태: `chat_replicator|streaming|async`
- replica recovery 상태: `true`
- 오래된 파티션이 없으면 archive worker는 `No partitions are older than retention window.`를 출력합니다.
- archive 대상 파티션이 있으면 `${partition}.csv`와 `${partition}.csv.metadata.json`이 함께 생성되고 Object Storage로 업로드됩니다. metadata에는 `sha256`, `bytes`, `rowCount`, `archivedAt`, `objectUri`, `metadataObjectUri`, `uploadedAt`이 포함됩니다.

### Object Storage / MinIO

Phase 8.3부터 Docker Compose는 S3 호환 Object Storage로 MinIO를 함께 띄웁니다. `minio-init`은 `${CHAT_OBJECT_STORAGE_BUCKET:-chat-archives}` bucket을 idempotent하게 생성하며, Object Storage를 실제로 사용하는 `chat-worker-app-1`, `chat-admin-app-1`, `postgres-partition-archive`만 `minio-init` 완료를 기다린 뒤 시작합니다. (API/WebSocket 앱은 bucket 초기화에 의존하지 않습니다.)

| 항목 | 기본값 |
| --- | --- |
| MinIO S3 API | `http://127.0.0.1:${MINIO_API_PORT:-9000}` |
| MinIO Console | `http://127.0.0.1:${MINIO_CONSOLE_PORT:-9001}` |
| Bucket | `chat-archives` |
| Admin export prefix | `admin-exports/` |
| Partition archive prefix | `postgres/archive/chat_messages/` |

admin export worker는 실행 중 checkpoint/resume에는 로컬 staging CSV를 사용하고, 완료 시 최종 CSV를 `s3://chat-archives/admin-exports/{jobId}.csv`에 업로드합니다. 관리자는 `GET /api/admin/exports/{jobId}`로 안정적인 `outputUri`와 만료 시간이 있는 `downloadUrl`을 조회합니다.

앱 컨테이너는 내부 endpoint(`CHAT_OBJECT_STORAGE_ENDPOINT=http://minio:9000`)로 업로드/조회하지만, presigned `downloadUrl`은 호스트 브라우저가 직접 접근해야 하므로 공개 endpoint(`CHAT_OBJECT_STORAGE_PUBLIC_ENDPOINT=http://127.0.0.1:${MINIO_API_PORT:-9000}`)로 서명됩니다. SigV4는 Host를 서명에 포함하므로 두 endpoint를 분리해야 컨테이너 내부 업로드와 호스트 브라우저 다운로드가 모두 동작합니다. 실제 AWS S3 환경에서는 공개 endpoint를 비워 두면 내부 endpoint로 폴백합니다.

partition archive worker는 detach/drop 전에 CSV와 metadata JSON을 `s3://chat-archives/postgres/archive/chat_messages/...` 아래로 업로드합니다. `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY=true`인데 Object Storage 업로드가 비활성화되었거나 실패하면 partition detach/drop은 수행하지 않습니다.

---

## Redis Cluster HA

Phase 8.2부터 전체 Docker backend의 기본 Redis topology는 `redis-cluster-node-1..6`과 `redis-cluster-init`으로 구성된 Redis Cluster다.

구성:

- `redis-cluster-node-1..3`: master 후보
- `redis-cluster-node-4..6`: replica 후보
- `redis-cluster-init`: 6개 node가 healthy가 된 뒤 `redis-cli --cluster create ... --cluster-replicas 1`을 idempotent하게 실행하는 one-shot gate
- Spring Boot 앱 컨테이너: `SPRING_PROFILES_ACTIVE=docker,redis-cluster`와 `REDIS_CLUSTER_NODES`로 Lettuce cluster mode를 사용

수동 기동:

```bash
docker compose --profile cluster up -d \
  redis-cluster-node-1 redis-cluster-node-2 redis-cluster-node-3 \
  redis-cluster-node-4 redis-cluster-node-5 redis-cluster-node-6 \
  redis-cluster-init
```

상태 확인:

```bash
docker compose exec -T redis-cluster-node-1 \
  redis-cli -p 6379 cluster info

docker compose exec -T redis-cluster-node-1 \
  redis-cli -p 6379 cluster nodes
```

로컬 호스트 Gradle 개발 모드(`mise run dev:*`)는 `mise run start:infra`가 명시적으로 시작하는 dev profile의 standalone `redis` 서비스를 사용한다. Redis Cluster 서비스와 전체 backend 앱/nginx/Prometheus/Grafana는 `cluster` profile에 격리되어 있어 `docker compose --profile dev up` 경로에서 함께 뜨지 않는다.

Redis Cluster host port는 `127.0.0.1`에만 bind한다. `infra/redis/redis-cluster.conf`는 `protected-mode no`가 필요하므로, Compose port binding으로 외부 인터페이스 노출을 막는다.

`infra/redis/redis-cluster.conf`는 `appendonly yes`, `appendfsync everysec`, `cluster-preferred-endpoint-type hostname`을 사용한다. 따라서 Redis node 장애나 host crash 시 마지막 fsync 이후 최대 1초의 Redis ingest가 손실될 수 있다. Phase 8.7은 Redis Streams `XADD MAXLEN`, canonical `room_seq` gap audit, WebSocket heartbeat로 Redis OOM 방어와 유실/좀비 연결 감지 경로를 제공한다.

Redis Streams append는 기본적으로 `CHAT_REDIS_STREAMS_MAX_LEN=1000000`, `CHAT_REDIS_STREAMS_MAX_LEN_APPROXIMATE=true`를 사용한다. 이 값은 stream key별 entry 상한이며, `0` 이하이면 bounded append를 비활성화한다. `MAXLEN`은 메시지 보존 보장이 아니라 Redis memory 보호용 backpressure이므로 worker lag, Redis memory, `chat.room_seq.gap.rooms`, `chat.room_seq.gap.missing_sequences`, `chat.room_seq.gap.max_width`, `chat.room_seq.gap.scanned_rooms` metric을 함께 봐야 한다.

`RoomSeqGapAuditWorker`는 `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_ENABLED=true`이고 `WORKER_ROLES`에 `room-seq-gap-audit`이 포함된 worker에서만 실행한다. 기본적으로 `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_POLL_DELAY_MILLIS=60000` 주기로 최근 `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_LOOKBACK=5m` window의 `chat_messages`를 message read datasource에서 스캔한다. audit 실패는 메시지 writer/fanout hot path를 막지 않고 warn log만 남긴다. gap metric은 자동 복구가 아니라 운영 감지 신호이며, sequence 발급 이후 append 실패로 생긴 hole 가능성이 있으므로 alert는 warning으로 시작해 Redis append failure, worker lag, canonical 저장 지표와 함께 판정한다.

WebSocket Gateway는 `CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_ENABLED=true`일 때 `CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_SCHEDULER_POLL_INTERVAL_MILLIS=10000` 주기로 열린 session의 heartbeat 상태를 점검하고, 마지막 ping 이후 `CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_INTERVAL_MILLIS=30000`이 지나면 protocol ping frame을 보낸다. `CHAT_WEBSOCKET_GATEWAY_HEARTBEAT_TIMEOUT_MILLIS=90000` 동안 pong frame이나 inbound message가 없으면 zombie connection으로 보고 `4004 Heartbeat timeout`으로 닫아 file descriptor와 outbound queue 상태를 회수한다.

`redis-cluster-init`은 `REDIS_CLUSTER_NODES`와 `REDIS_CLUSTER_BOOTSTRAP_TIMEOUT_SECONDS`를 사용한다. node 준비나 cluster convergence가 timeout 안에 끝나지 않으면 cluster diagnostics를 출력하고 실패한다.

### Phase 8.4 Hot Room Shard 분산

Phase 8.4부터 메시지 수락 경로는 `room_storage_configs`의 `current_shard_count`와 `fanout_shard_count`를 읽어 새 메시지의 `writeShard`, `streamShard`, `fanoutShard`를 계산한다. `writeShard`는 `messageId` hash 기반이고, `streamShard`와 `fanoutShard`는 `roomSeq` round-robin 기반이다.

shard 값은 수락 시점에 한 번 계산해 `MessageStreamEnvelope`에 담고, canonical writer는 이 값을 재계산 없이 그대로 PostgreSQL `chat_messages`에 기록한다. canonical PK가 `write_shard`를 포함하므로, ack 유실 후 shard 확장이 일어난 뒤 같은 메시지를 replay해도 produce 시점 `writeShard`를 유지해야 `ON CONFLICT DO NOTHING` 기준 idempotent insert가 보장된다. `chat_messages`는 `stream_shard`, `write_shard`, `fanout_shard` 컬럼을 모두 가진다.

`room-policy` worker는 `HOT=16`, `VERY_HOT=64`를 기본 shard count로 upsert한다. 자동 downgrade는 live feed window와 rate/slow-mode 정책은 갱신하지만 shard count를 줄이지 않는다. 이미 확장된 hot room이 다시 트래픽을 받을 때 단일 stream shard로 되돌아가 fanout owner 병목을 만들지 않기 위함이다.

10k release gate는 다음 명령으로 실행한다.

```bash
node scripts/phase8-hot-room-release-gate.mjs
```

이 명령은 `scripts/load-chat.mjs`로 10,000 msg/sec 60초 부하를 생성하고, Prometheus에서 fanout p95, stream shard 관측 수, Redis Streams group lag를 조회해 threshold를 넘으면 실패한다.

10,000 viewer gate는 같은 client IP에서 WebSocket ticket을 대량 발급하므로, backend를 시작하기 전에 `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP`를 viewer 수 이상으로 올리거나 부하 발생 IP를 분산해야 한다.

## 로드 밸런싱

Nginx는 역할별 upstream으로 트래픽을 분리합니다.

| 경로 | 대상 |
| --- | --- |
| `/api/ws/` | `chat-websocket-app-1~2` |
| `/api/admin/` | `chat-admin-app-1` |
| `/api/` | `chat-api-app-1~2` |

`chat-worker-app-1`은 외부 HTTP 트래픽을 받지 않고, Redis/DB 기반 비동기 작업을 담당합니다.

전체 시스템 아키텍처 구조와 구체적인 메시지 전송 및 실시간 전파 흐름은 [architecture_overview.md](./architecture_overview.md)를 참고하시기 바랍니다.

### Docker Compose Nginx DNS Stale

로컬 Compose에서 app 컨테이너를 재생성하면 컨테이너 IP가 바뀔 수 있습니다. Nginx는 일반적인 `upstream` 설정에서 hostname을 시작 시점에 resolve하므로, nginx를 재시작하지 않으면 이전 IP를 계속 바라볼 수 있습니다.

이전 API 컨테이너 IP가 WebSocket 컨테이너 같은 다른 role에 재사용되면 `/api/users/register` 같은 REST 요청이 잘못된 role로 전달되어 `404`가 발생할 수 있습니다. 이는 controller mapping 문제가 아니라 nginx upstream endpoint가 stale 상태가 된 것입니다.

현재 Compose runbook은 app rebuild 후 app health를 기다리고, nginx를 재시작한 뒤 nginx health까지 다시 기다리는 방식입니다. `mise run start`(내부적으로 `start:backend`)는 `docker compose --profile cluster up -d --build --wait` 이후 `docker compose restart nginx`를 실행합니다.

```bash
mise run start
mise run verify:chat
```

수동으로 앱 컨테이너를 재생성한 경우에는 다음 명령으로 nginx upstream DNS를 갱신합니다.

```bash
mise run restart:nginx
mise run verify:chat
```

상세 원인, 대안, 완료 기준은 [production_hardening_tasks.md](./production_hardening_tasks.md)의 `Docker Compose Nginx Upstream DNS Stale 대응` 항목을 따릅니다.

---

## 역할별 실행 모듈

| Compose 서비스 | 실행 모듈 | 역할 |
| --- | --- | --- |
| `chat-api-app-1~2` | `chat-api-application` | 사용자/방/메시지 REST API |
| `chat-websocket-app-1~2` | `chat-websocket-application` | WebSocket 연결 유지와 fan-out |
| `chat-worker-app-1` | `chat-worker-application` | message writer, fanout owner, admin export, room policy worker |
| `chat-admin-app-1` | `chat-admin-application` | 관리자 API |

로컬 Compose에서는 API/WebSocket처럼 고정된 서비스 이름으로 replica를 표현하거나, worker처럼 `docker compose up --scale chat-worker-app-1=2`로 동일 서비스 replica를 늘려 fanout owner lease를 검증할 수 있습니다. Phase 6 owner kill takeover는 `scripts/phase6-fanout-takeover-smoke.mjs`가 Redis owner lease value를 실제 worker container에 매핑해 검증합니다. Kubernetes로 옮길 때는 동일 실행 모듈을 Deployment별 replica 수로 조정합니다.

---

## 주요 설정 파일

| 파일 | 설명 |
| --- | --- |
| `infra/postgres/init-db.sql` | DB 초기화/튜닝 |
| `infra/postgres/primary/` | PostgreSQL primary 설정 |
| `infra/postgres/replica/` | PostgreSQL replica 설정 |
| `infra/postgres/message-partitions.sql` | 메시지 파티션 DDL |
| `infra/postgres/archive/` | 파티션 archive worker |
| `infra/redis/redis.conf` | host Gradle 개발 모드용 standalone Redis 설정 |
| `infra/redis/redis-cluster.conf` | 전체 Docker backend용 Redis Cluster node 설정 |
| `infra/redis/create-cluster.sh` | 3 master + 3 replica Redis Cluster bootstrap one-shot 스크립트 |
| `infra/nginx/nginx.conf` | Nginx 템플릿 설정(전체 클러스터용). Compose에서 `SERVER_PORT`를 `CHAT_BACKEND_PORT`로 전달해 upstream 포트를 생성 |
| `infra/nginx/nginx.dev.conf` | 로컬 개발용 Nginx 설정. upstream을 `host.docker.internal:8080~8082`(호스트 gradle 백엔드)로 라우팅. compose `nginx-dev`(profile `dev`) 서비스가 사용 |
| `start-cluster.sh` | 클러스터 실행 스크립트 |

---

## 운영 설계 문서

| 문서 | 설명 |
| --- | --- |
| [redis_cluster_key_naming.md](./redis_cluster_key_naming.md) | Redis Cluster key naming, hash slot, hash tag 정책 |
| [observability_metrics.md](./observability_metrics.md) | Phase 7 dashboard metric, alert, cardinality 정책 |
| [phase7_reconnect_load_test_scenarios.md](./phase7_reconnect_load_test_scenarios.md) | 정상 reconnect 실패율과 ticket rate limit 원자성 판단용 synthetic load test 시나리오 |
| [phase7_admin_search_latency_gate.md](./phase7_admin_search_latency_gate.md) | 관리자 검색 warm p95와 cold p99 release gate 측정 절차 |
| [phase7_admin_search_slow_query_plan_capture.md](./phase7_admin_search_slow_query_plan_capture.md) | 관리자 검색 cold p99 실패 시 PostgreSQL 실행 계획 수집 절차 |
| [phase7_redis_streams_direct_lag_gauge.md](./phase7_redis_streams_direct_lag_gauge.md) | Redis Streams group lag/pending direct gauge 운영 기준 |
| [phase7_redis_streams_lag_alert_rule.md](./phase7_redis_streams_lag_alert_rule.md) | Redis Streams lag/pending Prometheus alert rule 기준 |
| [architecture_overview.md](./architecture_overview.md) | 분산 웹소켓 서버 아키텍처 및 실시간 채팅 데이터 흐름 (Mermaid 다이어그램) |
