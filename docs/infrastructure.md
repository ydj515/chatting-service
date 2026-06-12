# 인프라 가이드

## mise 태스크

```bash
mise run setup                     # 도구 설치 및 클라이언트 의존성 설치
mise run build                     # 백엔드와 클라이언트 빌드
mise run clean:infra               # 인프라 볼륨 정리 (깨끗하게 재시작할 때)
mise run start:all                 # 빌드 + 인프라 + 앱 전체 기동
mise run start                     # 전체 클러스터 기동
mise run start:infra               # PostgreSQL primary/read-replica, archive worker, Redis 기동
mise run verify:postgres-replica   # PostgreSQL replica/archive 구성 검증
mise run verify:chat               # REST/WebSocket 채팅 흐름 검증
mise run stop                      # 전체 클러스터 종료
```

### 빠른 실행

기존 인프라를 정리하고 처음부터 깨끗하게 시작하려면:

```bash
mise run clean:infra   # 볼륨 정리
mise run start:all     # 빌드 + 전체 기동
```

---

## PostgreSQL Replica / Archive

### 구성 검증

```bash
docker compose up -d postgres postgres-primary-setup postgres-replica postgres-partition-archive

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

---

## 로드 밸런싱

Nginx는 역할별 upstream으로 트래픽을 분리합니다.

| 경로 | 대상 |
| --- | --- |
| `/api/ws/` | `chat-websocket-app-1~2` |
| `/api/admin/` | `chat-admin-app-1` |
| `/api/` | `chat-api-app-1~2` |

`chat-worker-app-1`은 외부 HTTP 트래픽을 받지 않고, Redis/DB 기반 비동기 작업을 담당합니다.

---

## 역할별 실행 모듈

| Compose 서비스 | 실행 모듈 | 역할 |
| --- | --- | --- |
| `chat-api-app-1~2` | `chat-api-application` | 사용자/방/메시지 REST API |
| `chat-websocket-app-1~2` | `chat-websocket-application` | WebSocket 연결 유지와 fan-out |
| `chat-worker-app-1` | `chat-worker-application` | writer/fanout/search/archive worker |
| `chat-admin-app-1` | `chat-admin-application` | 관리자 API |

로컬 Compose에서는 고정된 서비스 이름으로 replica를 표현합니다. Kubernetes로 옮길 때는 동일 실행 모듈을 Deployment별 replica 수로 조정합니다.

---

## 주요 설정 파일

| 파일 | 설명 |
| --- | --- |
| `infra/postgres/init-db.sql` | DB 초기화/튜닝 |
| `infra/postgres/primary/` | PostgreSQL primary 설정 |
| `infra/postgres/replica/` | PostgreSQL replica 설정 |
| `infra/postgres/message-partitions.sql` | 메시지 파티션 DDL |
| `infra/postgres/archive/` | 파티션 archive worker |
| `infra/redis/redis.conf` | Redis 설정 |
| `infra/nginx/nginx.conf` | Nginx 템플릿 설정. Compose에서 `SERVER_PORT`를 `CHAT_BACKEND_PORT`로 전달해 upstream 포트를 생성 |
| `start-cluster.sh` | 클러스터 실행 스크립트 |
