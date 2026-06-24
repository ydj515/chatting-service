# Phase 7 Admin Search Slow Query Plan Capture

이 문서는 관리자 검색 cold p99 gate 실패 시 PostgreSQL 실행 계획을 수집하는 방법을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- `scripts/measure-admin-search-p95.mjs`는 warm p95와 cold p99 release gate를 이미 계산한다.
- cold p99 실패는 threshold 초과 사실만 보여주며, 어떤 PostgreSQL plan이 병목인지 직접 남기지 않았다.
- 운영자는 실패 run과 같은 room, query, time window, mode 조건으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` artifact를 확보해야 한다.
- plan capture는 실제 쿼리를 실행하므로 기본값은 비활성화한다.

### 목표

- `--slow-query-plan on-cold-failure` 옵션을 추가한다.
- cold gate가 실패한 endpoint에 대해서만 plan capture를 수행한다.
- capture 결과는 JSON artifact로 저장하고 top-level report의 `slowQueryPlans`에 경로와 상태를 기록한다.
- psql 실행은 기존 `scripts/lib/psqlCommand.mjs` helper를 재사용한다.

## 2. 실행 명령

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --gate cold \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --from 2026-06-13T11:46:50 \
  --to 2026-06-14T15:33:25 \
  --search-mode FTS \
  --requests 100 \
  --concurrency 5 \
  --target-cold-p99-ms 6000 \
  --slow-query-plan on-cold-failure \
  --slow-query-plan-output-dir docs/performance/admin-search-slow-query-plans \
  --psql-mode docker-compose \
  --psql-service postgres-replica \
  --output docs/performance/phase7_admin_search_cold.json
```

기본 DB 옵션:

| 옵션 | 기본값 |
| --- | --- |
| `--host` | `DB_READ_HOST`, `DB_HOST`, 없으면 `localhost` |
| `--port` | `DB_READ_PORT`, `DB_PORT`, 없으면 `5432` |
| `--database` | `DB_NAME`, 없으면 `chatdb` |
| `--username` | `DB_USERNAME`, 없으면 `chatuser` |
| `--password` | `DB_PASSWORD`, 없으면 `chatpass` |
| `--psql-mode` | `local` |
| `--psql-service` | `postgres-replica` |

## 3. JSON Summary

Top-level report에 다음 필드가 추가된다.

| Field | 의미 |
| --- | --- |
| `options.slowQueryPlan` | `off` 또는 `on-cold-failure` |
| `options.slowQueryPlanOutputDir` | artifact 저장 디렉터리 |
| `options.psql` | capture가 켜졌을 때 psql 접속 정보. password는 제외 |
| `slowQueryPlans[]` | capture 대상별 상태 |

`slowQueryPlans[]` 항목:

| Field | 의미 |
| --- | --- |
| `endpointName` | `history` 또는 `search` |
| `gate` | 현재는 `cold` |
| `targetMetric` | 실패 판정 metric. 일반적으로 `p99Ms` |
| `measuredMs` | 실패 run의 측정값 |
| `targetMs` | gate threshold |
| `status` | `captured` 또는 `failed` |
| `artifactPath` | 저장된 plan artifact 경로 |
| `error` | capture 실패 시 오류 메시지 |

## 4. Artifact 형식

artifact는 JSON 파일이며 다음 정보를 포함한다.

- `measuredAt`
- endpoint/gate/threshold metadata
- app repository와 같은 형태의 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` SQL
- PostgreSQL JSON plan을 parse한 `explain`
- parse 실패 시에도 확인 가능한 `rawExplain`

## 5. 복잡도

- capture 대상 선정 시간 복잡도: `O(S * G)`
- SQL 생성 시간 복잡도: `O(1)`
- EXPLAIN 실행 시간 복잡도: PostgreSQL optimizer와 실제 query plan에 의존한다.
- report 공간 복잡도: `O(C + P)`

여기서 `S`는 endpoint 수, `G`는 gate phase 수, `C`는 capture 대상 수, `P`는 plan artifact 크기다.

## 6. 주의사항

> - `EXPLAIN (ANALYZE)`는 실제 쿼리를 실행한다. 운영 부하가 민감한 시간대에는 신중하게 사용한다.
> - artifact에는 query text와 SQL이 들어가므로 민감한 검색어를 외부에 공유하기 전에 검토한다.
> - script는 cold cache를 만들지 않는다. cold run 조건은 app restart, DB restart, 또는 별도 synthetic window로 준비해야 한다.
> - plan capture 실패는 latency report 생성을 막지 않고 `slowQueryPlans[].status = failed`로 남긴다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| runner에서 on-demand psql capture | 실패 run과 같은 조건을 즉시 artifact로 남긴다 | psql 접근 권한과 실행 비용이 필요하다 | 선택 |
| DB server log 또는 auto_explain 사용 | 운영 DB 관점에서 자동 수집 가능 | 환경 설정과 log pipeline 의존성이 커진다 | 후속 |
| 수동 EXPLAIN runbook만 문서화 | 구현 변경이 작다 | 실패 순간의 조건과 artifact가 누락되기 쉽다 | 보류 |

## 8. 후속 질문

- slow query plan artifact를 CI artifact 또는 PR comment로 자동 업로드할 것인가?
- `CONTAINS` fallback은 별도 non-blocking diagnostic threshold를 둘 것인가?
- auto_explain 기반 상시 수집으로 확장할 것인가?
