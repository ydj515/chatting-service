# Phase 7 Admin Search Slow Query Plan Capture 설계서

- 작성일: 2026-06-24
- 슬라이스: Admin search slow query plan capture
- 상태: 구현 완료

## 1. 문제 이해 / 요구사항 정리

### 조건

- Admin search warm/cold latency gate는 `ok`, `failedGates`, `gateResults`를 계산한다.
- cold p99 실패가 발생하면 threshold 초과 사실은 알 수 있지만 PostgreSQL 실행 계획은 남지 않는다.
- Phase 5 tuning 기록은 cold `EXPLAIN (ANALYZE, BUFFERS)`가 tail latency 원인 분석에 유효하다는 근거를 이미 남겼다.
- plan capture는 실제 query를 실행하므로 항상 자동 실행하면 운영 부하와 권한 요구가 커진다.

### 목표

- cold gate 실패 시 선택적으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 수집한다.
- 기본값은 `off`로 두고, 운영자가 `--slow-query-plan on-cold-failure`를 명시했을 때만 실행한다.
- 실패한 cold gate endpoint만 capture 대상으로 삼는다.
- capture artifact와 report contract를 JSON으로 남긴다.
- 외부 npm dependency를 추가하지 않고 기존 psql command helper를 재사용한다.

### 비범위

- PostgreSQL query tuning 자체는 하지 않는다.
- DB/page cache drop 또는 restart orchestration은 하지 않는다.
- auto_explain, pg_stat_statements, tracing pipeline은 추가하지 않는다.

## 2. 해결 접근

### 선택한 접근

`scripts/lib/adminSearchPlanCapture.mjs`를 새로 두고 pure helper와 psql 실행 helper를 분리한다.

Runner 흐름:

1. 기존 HTTP sample과 gate summary를 계산한다.
2. `buildSlowQueryCaptureRequests()`가 `gate === cold`이고 `passedTarget === false`인 항목만 고른다.
3. endpoint 이름과 CLI option으로 app repository와 같은 형태의 SQL을 생성한다.
4. `psql` 또는 `docker compose exec ... psql`로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행한다.
5. artifact를 `docs/performance/admin-search-slow-query-plans` 아래 저장하고 report의 `slowQueryPlans`에 상태를 기록한다.

### 이유

- 기존 latency runner workflow를 유지하면서 실패 분석 artifact만 덧붙일 수 있다.
- 대상 선정과 SQL 생성은 DB 없이 테스트할 수 있다.
- psql 실행은 기존 seed script와 같은 운영 모델을 따른다.
- capture 실패가 release gate summary를 덮어쓰지 않으므로 실패 원인이 보존된다.

## 3. Summary Contract

| Field | 의미 |
| --- | --- |
| `options.slowQueryPlan` | `off` 또는 `on-cold-failure` |
| `options.slowQueryPlanOutputDir` | artifact 저장 디렉터리 |
| `options.slowQueryPlanTimeoutMs` | psql capture subprocess timeout |
| `options.psql` | password를 제외한 psql 접속 정보 |
| `slowQueryPlans[]` | endpoint별 capture 결과 |
| `slowQueryPlans[].status` | `captured` 또는 `failed` |
| `slowQueryPlans[].artifactPath` | 생성된 JSON artifact 경로 |

## 4. 테스트 전략

- capture 대상 선정이 failed cold gate만 포함하는지 검증한다.
- FTS search EXPLAIN SQL이 `plainto_tsquery`, room/time filter, ordering, limit을 포함하는지 검증한다.
- query literal의 single quote escaping을 검증한다.
- history endpoint EXPLAIN SQL이 room/time bounded query와 history ordering을 유지하는지 검증한다.
- CLI의 invalid `--slow-query-plan` 값이 명확한 option error를 내는지 검증한다.
- capture off 상태에서는 잘못된 psql 옵션이 일반 측정을 막지 않는지 검증한다.
- hanging psql process가 timeout으로 실패 처리되는지 검증한다.

## 5. 복잡도

- capture 대상 선정 시간 복잡도: `O(S * G)`
- SQL 생성 시간 복잡도: `O(1)`
- artifact metadata 공간 복잡도: `O(C)`
- artifact 저장 공간 복잡도: `O(P)`

여기서 `S`는 endpoint 수, `G`는 gate phase 수, `C`는 capture 대상 수, `P`는 PostgreSQL plan JSON 크기다.

## 6. 주의사항

> - `EXPLAIN (ANALYZE)`는 실제 query를 실행하므로 production에서 무분별하게 켜면 부하가 생길 수 있다.
> - artifact에는 query와 SQL text가 포함된다. 민감 검색어가 들어갈 수 있으므로 공유 전 검토가 필요하다.
> - `--gate both`에서는 cold phase가 먼저 실행되며, slow query plan capture도 cold 실패 항목에만 붙는다.
> - psql 접속 실패는 report 생성 실패가 아니라 capture 실패로 기록한다.
> - psql process가 timeout을 넘기면 capture는 실패로 남기고 measurement report 생성을 계속한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| runner option으로 on-demand capture | 실패 조건과 같은 파라미터로 즉시 plan 확보 | psql 권한과 실행 비용 필요 | 선택 |
| auto_explain 설정 | 실제 slow query를 DB가 자동 기록 | 환경 설정과 log 수집 의존성 큼 | 후속 |
| pg_stat_statements 조회 | 운영 query 집계에 강함 | 개별 실패 run의 parameter와 plan이 직접 남지 않음 | 보조 |

## 8. 후속 질문

- artifact retention 기간과 저장 위치를 별도 정책으로 둘 것인가?
- psql capture를 read replica 대신 primary fallback으로도 허용할 것인가?
- slow query plan capture 실패를 release blocking으로 볼 것인가?
