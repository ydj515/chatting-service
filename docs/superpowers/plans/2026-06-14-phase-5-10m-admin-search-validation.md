# Phase 5 10M Admin Search Validation Plan

**Goal:** Phase 5 admin API가 실제 10,000,000건 seed 데이터에서 방별 history와 keyword search p95 목표를 만족하는지 로컬 Docker Compose 환경에서 측정한다.

**Target:** admin history/search p95 `<= 1000ms`

## Scope

- PostgreSQL primary/read-replica, role-separated applications, Nginx를 Docker Compose로 기동한다.
- `scripts/seed-admin-search-messages.mjs`로 10,000,000건 메시지를 실제 insert한다.
- `scripts/measure-admin-search-p95.mjs`로 `history`, `search` 시나리오를 측정한다.
- 결과 JSON과 Markdown summary를 `docs/performance/`에 남긴다.
- 기존 untracked `docs/questions/`는 이 태스크 범위 밖이므로 건드리지 않는다.

## Work Items

- [x] Seed script backpressure 보강
- [x] Admin p95 측정 CLI 추가
- [x] Docker Compose cluster 기동
- [x] 10M seed 실제 실행
- [x] Admin history/search p95 측정
- [x] 결과 문서화
- [x] 검증 명령 재실행
- [x] 슬라이스별 커밋

## Commands

```bash
mise run start:all
node scripts/seed-admin-search-messages.mjs \
  --messages 10000000 \
  --rooms normal:60,hot:30,very-hot:10 \
  --batch-size 100000 \
  --seed-id phase5_10m_20260614 \
  --psql-mode docker-compose

node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --requests 100 \
  --warmup 10 \
  --concurrency 5 \
  --target-p95-ms 1000 \
  --output docs/performance/phase5_admin_search_10m_2026-06-14.json
```

## Complexity

- 10M seed: `O(M)` time, `O(1)` application memory after stream backpressure handling, `M = 10,000,000`.
- History p95 measurement: `O(R * (log N_room + L))`, `R` is request count and `L` is response limit.
- Search p95 measurement: `O(R * (log N_text + L))` under PostgreSQL GIN/FTS index assumptions.

## Caveats

> - 로컬 Docker Desktop 디스크/CPU 성능은 운영 DB와 다르므로 결과는 release gate가 아니라 Phase 5 구현 검증 기준이다.
> - Seed 데이터의 일부 시간 범위가 기본 partition으로 들어갈 수 있으므로 partition pruning 결과를 해석할 때 주의한다.
> - p95가 목표를 넘으면 PostgreSQL 튜닝, partition 범위, search index, read replica lag를 먼저 확인하고 OpenSearch는 이후 대안으로 검토한다.
