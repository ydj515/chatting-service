# Phase 7 Admin Search Latency Gate

이 문서는 관리자 검색의 warm p95와 cold p99 release gate를 실행하고 판정하는 방법을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 5에서 PostgreSQL-first admin history/search 성능을 10M row dataset으로 검증했다.
- Phase 7 release gate는 steady-state warm p95와 post-restart/cold-cache p99를 분리해서 판단한다.
- `scripts/measure-admin-search-p95.mjs`는 기존 p95 측정 스크립트 이름을 유지하지만, `--gate` 옵션으로 cold p99도 함께 계산한다.

### 목표

- warm gate: explicit warmup 이후 history/search p95가 `1000ms` 이하이고 실패 응답이 없어야 한다.
- cold gate: warmup 없는 post-restart 또는 cold-cache synthetic run에서 p99가 `6000ms` 이하이고 실패 응답이 없어야 한다.
- `--gate both` 실행 결과는 `ok`, `failedGates`, `gateResults`로 warm/cold 실패 원인을 분리한다.

## 2. 실행 명령

### Warm Gate

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --gate warm \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --from 2026-06-13T11:46:50 \
  --to 2026-06-14T15:33:25 \
  --search-mode FTS \
  --requests 100 \
  --warmup 30 \
  --concurrency 5 \
  --target-warm-p95-ms 1000 \
  --output docs/performance/phase7_admin_search_warm.json
```

### Cold Gate

Cold gate는 app restart 직후 또는 DB/page cache가 차가운 synthetic window에서 실행한다. 스크립트는 cold cache를 직접 만들지 않고 warmup을 생략해 판정 contract를 고정한다.

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
  --warmup 30 \
  --concurrency 5 \
  --target-cold-p99-ms 6000 \
  --output docs/performance/phase7_admin_search_cold.json
```

`--gate cold`에서는 `--warmup` 값이 있어도 measurement 전 warmup request를 실행하지 않는다.

### Combined Gate

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --gate both \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --from 2026-06-13T11:46:50 \
  --to 2026-06-14T15:33:25 \
  --search-mode FTS \
  --requests 100 \
  --warmup 30 \
  --concurrency 5 \
  --target-warm-p95-ms 1000 \
  --target-cold-p99-ms 6000 \
  --output docs/performance/phase7_admin_search_both.json
```

`--gate both`는 같은 process에서 각 endpoint별 cold sample을 먼저 수집한 뒤, warmup과 warm sample을 실행한다.

## 3. JSON Summary

Top-level fields:

| Field | 의미 |
| --- | --- |
| `gate` | `warm`, `cold`, `both` |
| `ok` | 모든 gate가 threshold와 failure 조건을 통과하면 `true` |
| `failedGates` | 실패 gate 이름. 예: `admin_search_cold_p99:search` |
| `targetWarmP95Ms` | warm p95 threshold |
| `targetColdP99Ms` | cold p99 threshold |
| `results[].gateResults[]` | endpoint별 gate summary |

Gate summary fields:

| Field | 의미 |
| --- | --- |
| `gate` | `warm` 또는 `cold` |
| `targetMetric` | `p95Ms` 또는 `p99Ms` |
| `targetMs` | 해당 gate threshold |
| `measuredMs` | 판정에 사용한 percentile 값 |
| `passedTarget` | 실패 응답이 없고 measured latency가 threshold 이하이면 `true` |

## 4. 복잡도

- request plan 생성 시간 복잡도: `O(S)`
- sample 실행 네트워크 복잡도: `O(S * G * N)`
- percentile 계산 시간 복잡도: `O(N log N)`
- report 공간 복잡도: `O(S * G * N)`

여기서 `S`는 endpoint scenario 수, `G`는 gate phase 수, `N`은 gate별 request 수다.

## 5. 주의사항

> - `--gate cold`는 운영 환경의 page cache를 직접 비우지 않는다. app restart, DB restart, cold-cache synthetic window 같은 외부 조건과 함께 실행해야 cold gate 의미가 생긴다.
> - `--gate both`의 warm 측정은 같은 process의 cold 측정 이후 실행되므로, 순수 cold-only 증거가 필요하면 `--gate cold`를 별도 실행한다.
> - warm p95 통과는 cold p99 통과를 의미하지 않는다.
> - `CONTAINS` mode는 operator fallback이며 기본 release gate는 `FTS` 기준이다.

## 6. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 기존 측정 스크립트 확장 | 기존 seed/measure workflow와 호환되고 변경 범위가 작다 | 파일명에 `p95`가 남아 cold p99 역할이 덜 드러난다 | 선택 |
| 새 Phase 7 전용 스크립트 추가 | release gate 책임이 명확하다 | HTTP 측정 로직이 중복된다 | 보류 |
| 서버 metric만 사용 | dashboard와 alert에 바로 연결된다 | post-restart/cold synthetic denominator를 분리하기 어렵다 | 보조 |

## 7. 후속 질문

- cold p99 실패 시 `EXPLAIN (ANALYZE, BUFFERS)` 자동 수집을 붙일 것인가?
- `CONTAINS` fallback에도 별도 operator-only threshold를 둘 것인가?
- CI에서 작은 synthetic fixture로 JSON contract만 검증할 것인가?
