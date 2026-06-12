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

Nginx가 `chat-app-1~3`으로 라운드로빈 분산합니다.  
WebSocket 경로 `/api/ws/`도 동일하게 프록시됩니다.

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
| `infra/nginx/nginx.conf` | Nginx 설정 |
| `start-cluster.sh` | 클러스터 실행 스크립트 |
