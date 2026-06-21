# Phase 7 슬라이스 문서 우선 원칙

이 문서는 Phase 7 이후 작업을 슬라이스 단위로 진행할 때, 구현보다 문서를 먼저 작성하는 기준과 순서를 정리한다. 각 슬라이스의 상세 설계는 이 문서에 합치지 않고 별도 설계 문서로 작성한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 7은 단일 기능 개발이 아니라 운영 검증, 계측, load/chaos test, release gate를 여러 조각으로 닫는 단계다.
- 각 슬라이스는 metric semantics, release gate, synthetic check, 운영 runbook, 장애 판정 기준을 바꿀 수 있다.
- 구현부터 시작하면 어떤 지표를 성공으로 볼지, 어떤 실패를 release blocking으로 볼지 흐려질 수 있다.
- 따라서 운영 기준이나 검증 기준을 바꾸는 슬라이스는 문서를 먼저 작성한다.

### 목표

- 각 Phase 7 슬라이스의 범위와 비범위를 구현 전에 고정한다.
- 각 슬라이스마다 독립 설계 문서가 남게 한다.
- 구현자가 같은 기준으로 테스트, 계측, release gate를 해석하게 한다.
- 후속 리뷰에서 "무엇을 완료로 볼 것인가"를 문서 기준으로 판단한다.
- Compose 한정 대응과 Kubernetes 전환 후에도 유지할 대응을 분리한다.

## 2. 문서 우선 적용 기준

다음 중 하나라도 해당하면 구현 전에 슬라이스 설계 문서를 먼저 작성한다.

- release gate 기준을 새로 추가하거나 바꾼다.
- metric 이름, tag, cardinality, alert 기준을 정의하거나 바꾼다.
- load test, chaos test, synthetic check의 pass/fail 기준을 정의한다.
- 장애 runbook, rollback 기준, 운영 절차를 바꾼다.
- raw diagnostic signal과 client-visible release blocking signal을 분리해야 한다.
- Phase7-pre 또는 Phase8로 넘길 범위를 잘라내야 한다.

작은 코드 정리, 오타 수정, 테스트 내부 리팩터링처럼 운영 기준을 바꾸지 않는 변경은 별도 설계 문서 없이 진행할 수 있다.

## 3. 슬라이스 진행 순서

1. 슬라이스 후보를 고른다.
2. 관련 기존 문서와 코드를 확인한다.
3. `docs/superpowers/specs/YYYY-MM-DD-phase7-<topic>-design.md`에 해당 슬라이스만 다루는 독립 설계 문서를 작성한다.
4. 문서에 조건, 목표, 접근안, 복잡도, 주의사항, 대안, 후속 질문을 포함한다.
5. 문서를 자체 검토한다.
6. 사용자가 승인하면 구현 계획을 작성한다.
7. TDD로 구현한다.
8. 검증 결과와 남은 리스크를 문서나 최종 보고에 남긴다.
9. `docs/phase7_slices.md` 인덱스에 슬라이스 상태와 문서 링크를 갱신한다.

## 4. 우선순위 후보

| 순서 | 슬라이스 | 문서 우선 이유 |
| --- | --- | --- |
| 1 | Nginx stale upstream synthetic check | Phase 7 첫 release gate이며 Compose 운영 절차와 연결된다 |
| 2 | Fanout owner kill takeover summary 확장 | raw delivery와 client-visible 결과를 분리해야 한다 |
| 3 | WebSocket reconnect synthetic load test | 정상 reconnect 실패율, cohort, rate limit UX 기준이 필요하다 |
| 4 | Redis Streams lag/pending 및 writer latency 계측 | metric semantics와 alert 기준을 먼저 정해야 한다 |
| 5 | Admin search warm/cold latency gate | warm p95와 cold p99를 분리해 판정해야 한다 |
| 6 | Chaos test runbook | 장애 주입 범위와 release blocking 조건을 먼저 합의해야 한다 |

## 5. 복잡도

- 슬라이스 문서 작성 시간 복잡도: `O(D + C)`
- 슬라이스 검토 시간 복잡도: `O(D + R)`
- 공간 복잡도: `O(D)`

여기서 `D`는 관련 문서 수, `C`는 확인할 코드 경로 수, `R`은 요구사항과 release gate 항목 수다.

## 6. 주의사항

> - 문서 우선은 구현 지연을 위한 절차가 아니라 release gate의 의미를 흐리지 않기 위한 안전장치다.
> - 모든 변경에 문서를 요구하지 않는다. 운영 기준이나 검증 기준을 바꾸는 슬라이스에만 적용한다.
> - 문서에는 반드시 비범위를 적는다. Phase7-pre, Phase8, Kubernetes 전환 작업이 Phase7 본체에 섞이지 않게 하기 위함이다.
> - metric tag에는 session token, WebSocket ticket, raw IP, message body 같은 민감값을 넣지 않는 원칙을 반복 확인한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 슬라이스 문서 우선 | release gate와 운영 기준이 명확해진다 | 작은 변경에는 절차가 무겁다 | Phase 7 기본 |
| 구현 후 문서 보강 | 빠르게 코드를 만들 수 있다 | 테스트가 구현 결과에 끌려가고 기준이 흔들릴 수 있다 | 운영 기준 변경에는 부적합 |
| 하나의 거대 Phase 7 문서 | 전체 그림을 한 곳에서 볼 수 있다 | 슬라이스별 승인과 변경 추적이 어렵다 | 인덱스 문서로만 보조 |

## 8. 후속 질문

- 다음 슬라이스를 fanout owner kill takeover summary 확장으로 둘 것인가?
- reconnect/load test metric 설계를 takeover summary보다 먼저 둘 필요가 있는가?
- 각 슬라이스 문서를 커밋 단위로 독립 관리할 것인가?
