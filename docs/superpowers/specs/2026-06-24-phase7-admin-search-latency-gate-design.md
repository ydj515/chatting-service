# Phase 7 Admin Search Latency Gate 설계서

- 작성일: 2026-06-24
- 슬라이스: Admin search warm/cold latency gate
- 상태: 구현 완료

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 5 admin search tuning 문서는 warm p95와 cold p99를 별도 release gate로 다루라고 정의했다.
- `scripts/measure-admin-search-p95.mjs`는 기존 warmup 기반 p95 측정은 가능하지만 cold p99 gate와 top-level `ok`/`failedGates` contract가 없다.
- 운영자는 history와 FTS search를 같은 runner로 측정하되, warm 실패와 cold 실패를 다른 release blocking reason으로 봐야 한다.
- cold cache 자체를 script가 강제로 만들면 환경 의존성과 권한 요구가 커지므로, 이번 슬라이스는 measurement window와 gate contract를 고정한다.

### 목표

- `--gate warm|cold|both` 옵션을 추가한다.
- warm gate는 warmup 후 `p95 <= targetWarmP95Ms`와 실패 응답 0건으로 판정한다.
- cold gate는 warmup 없이 `p99 <= targetColdP99Ms`와 실패 응답 0건으로 판정한다.
- report에 `ok`, `failedGates`, `gateResults`를 추가한다.
- 기존 `--target-p95-ms`는 warm threshold alias로 유지한다.
- Phase 7 운영 문서와 슬라이스 인덱스를 갱신한다.

### 비범위

- PostgreSQL query plan 튜닝은 하지 않는다.
- DB page cache drop, container restart, app restart orchestration은 하지 않는다.
- 서버 Micrometer metric은 추가하지 않는다.

## 2. 해결 접근

### 선택한 접근

기존 `scripts/measure-admin-search-p95.mjs`를 확장하고, 판정 로직은 `scripts/lib/adminLatencyStats.mjs`와 `scripts/lib/adminMeasurePlan.mjs`에 둔다.

Runner 흐름:

1. `scenario`로 history/search endpoint plan을 만든다.
2. `gate`로 실행 phase를 만든다. `both`는 `cold`, `warm` 순서다.
3. cold phase는 warmup 없이 sample을 수집하고 p99 threshold로 판정한다.
4. warm phase는 configured warmup을 실행한 뒤 sample을 수집하고 p95 threshold로 판정한다.
5. endpoint별 gate result를 모아 top-level `ok`와 `failedGates`를 계산한다.

### 이유

- 기존 측정 command와 performance 문서를 유지하면서 Phase 7 gate semantics만 추가할 수 있다.
- pure helper를 테스트하면 HTTP 서버 없이 gate contract를 검증할 수 있다.
- cold orchestration을 섞지 않아 로컬과 운영 환경의 책임 경계가 명확하다.

## 3. Summary Contract

| Field | 의미 |
| --- | --- |
| `gate` | `warm`, `cold`, `both` |
| `ok` | 모든 gate summary가 threshold와 failure 조건을 통과하면 `true` |
| `failedGates` | `admin_search_<gate>_<percentile>:<endpoint>` 형식 |
| `results[].gateResults[].summary.targetMetric` | `p95Ms` 또는 `p99Ms` |
| `results[].gateResults[].summary.measuredMs` | 판정에 사용한 latency |

## 4. 테스트 전략

- `buildGatePhases()`가 `both`를 cold-first로 만드는지 검증한다.
- `summarizeGateSamples()`가 warm p95와 cold p99 threshold를 분리하는지 검증한다.
- `summarizeGateReport()`가 안정적인 `failedGates` 이름을 만드는지 검증한다.
- script syntax check로 CLI wiring을 검증한다.
- 기존 전체 Node script test를 실행해 회귀를 확인한다.

## 5. 복잡도

- gate phase 생성 시간 복잡도: `O(1)`
- endpoint plan 생성 시간 복잡도: `O(S)`
- sample 실행 시간 복잡도: `O(S * G * N)`
- percentile 계산 시간 복잡도: `O(N log N)`
- report 공간 복잡도: `O(S * G * N)`

여기서 `S`는 endpoint scenario 수, `G`는 gate phase 수, `N`은 phase별 request 수다.

## 6. 주의사항

> - cold gate는 cold 환경을 만들어내는 기능이 아니라, warmup 없는 measurement window를 cold p99 threshold로 판정하는 기능이다.
> - `--gate both`는 cold evidence와 warm evidence를 한 report에 담지만, 완전히 독립된 cold-only run이 필요한 배포 검증에서는 `--gate cold`를 별도 실행한다.
> - 기존 `--target-p95-ms`는 backward compatibility alias이며 새 문서에서는 `--target-warm-p95-ms`를 우선 사용한다.
> - 실패 응답이 1건이라도 있으면 percentile이 threshold 이하라도 gate는 실패한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 기존 script 확장 | 기존 workflow와 호환되고 변경 범위가 작다 | script 이름이 cold p99까지 완전히 표현하지 못한다 | 선택 |
| 새 `phase7-admin-search-gate.mjs` 추가 | Phase 7 gate 책임이 명확하다 | HTTP sampling 코드가 중복된다 | 보류 |
| 서버 metric 우선 구현 | dashboard와 직접 연결된다 | synthetic cold/post-restart denominator가 흐려진다 | 후속 |

## 8. 후속 질문

- cold p99 실패 시 slow query plan capture를 자동화할 것인가?
- 운영 dashboard에서는 synthetic summary와 서버 metric 중 어느 쪽을 release blocking source로 삼을 것인가?
- `CONTAINS` fallback을 별도 non-blocking diagnostic gate로 둘 것인가?
