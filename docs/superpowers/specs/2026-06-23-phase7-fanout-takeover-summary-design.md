# Phase 7 Fanout Takeover Summary Design

- 작성일: 2026-06-23
- 슬라이스: Fanout owner kill takeover summary 확장
- 상태: 설계 승인됨

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 6 fanout owner lease는 Redis TTL lease와 fencing token으로 같은 room shard의 active owner를 하나로 제한한다.
- Redis TTL owner lease는 exactly-once delivery를 보장하지 않는다. owner worker가 publish 후 ack 전에 죽으면 takeover worker가 같은 stream record를 다시 publish할 수 있다.
- 2026-06-20 사전 점검에서 owner kill takeover smoke가 `roomSeq order violated: 77 came after 600`으로 1회 실패했고, 동일 조건 재실행은 통과했다.
- 현재 `scripts/load-chat.mjs`는 `--assert-room-seq-order`가 켜지면 raw 수신 배열에서 `roomSeq` 역전을 발견하는 즉시 실패한다.
- 기존 Phase 7 문서는 owner kill/takeover 경로에서 raw delivery order를 diagnostic signal로 유지하고, release blocking 기준은 client-visible dedupe/render 결과로 삼기로 했다.
- steady-state와 무장애 다중 worker 경로에서는 raw `roomSeq` 역전이 없어야 한다.

### 목표

- owner kill/takeover smoke에서 raw delivery 역전을 즉시 실패로만 처리하지 않고, 어떤 종류의 역전인지 summary로 남긴다.
- raw delivery diagnostic과 client-visible release gate를 분리한다.
- duplicate replay와 first-seen late delivery를 구분한다.
- `messageId` dedupe 후 `roomSeq` 정렬 결과가 중복 없이 안정적인지 확인한다.
- 기존 steady-state raw order assertion 동작은 유지한다.
- Phase 7 슬라이스 인덱스와 관련 운영 문서를 갱신한다.

### 비범위

- `HotRoomFanoutWorker`의 publish/ack 알고리즘 자체를 수정하지 않는다.
- Redis owner lease TTL, renew interval, pending claim 정책을 바꾸지 않는다.
- WebSocket reconnect synthetic load test와 ticket rate limit cohort metric은 다음 슬라이스에서 다룬다.
- 실제 브라우저 client UI의 render 코드는 수정하지 않는다. 이번 슬라이스의 client-visible 기준은 smoke runner가 `messageId` dedupe와 `roomSeq` sort를 시뮬레이션한 결과다.

## 2. 선택한 접근

### 접근 A: raw diagnostic + client-visible gate

`load-chat`에 takeover summary 분석 모드를 추가한다. 이 모드는 viewer별 수신 메시지를 순회하면서 다음 값을 계산한다.

- `raw.inversionCount`: raw 수신 순서에서 이전 최대 `roomSeq`보다 낮은 메시지가 도착한 횟수
- `raw.duplicateReplayCount`: 이미 본 `messageId`가 다시 도착한 횟수
- `raw.firstSeenLateDeliveryCount`: 처음 보는 `messageId`인데 이전 최대 `roomSeq`보다 낮게 도착한 횟수
- `clientVisible.uniqueCount`: `messageId` dedupe 후 남은 메시지 수
- `clientVisible.sorted`: dedupe 후 `roomSeq` 오름차순 정렬 결과가 유효한지
- `clientVisible.duplicateCount`: client-visible 결과에서 제거된 중복 수
- `releaseBlocking`: first-seen late delivery가 있거나 client-visible 결과가 유효하지 않으면 `true`

takeover smoke는 이 summary를 그대로 포함해 최종 JSON에 출력한다. steady-state 검증은 기존 `--assert-room-seq-order`를 계속 사용한다.

### 이유

- 기존 문서의 production takeover 기준과 가장 잘 맞는다.
- duplicate replay와 실제 사용자 화면 흔들림을 분리해 flake 원인을 더 정확히 판단할 수 있다.
- backend fanout 알고리즘을 고치기 전에 관측 기준을 먼저 안정화할 수 있다.
- 이후 reconnect/load/chaos test가 실패했을 때 fanout takeover 문제인지 다른 계층 문제인지 구분하기 쉬워진다.

## 3. 데이터 흐름

1. `phase6-fanout-takeover-smoke.mjs`가 `load-chat.mjs`를 실행한다.
2. `load-chat.mjs`는 viewer별 raw `CHAT_MESSAGE`와 `CHAT_MESSAGE_BATCH` payload를 기존처럼 배열에 저장한다.
3. takeover summary 모드가 켜진 경우, viewer별 raw 배열을 `summarizeTakeoverDelivery()`에 전달한다.
4. `summarizeTakeoverDelivery()`는 raw diagnostic summary와 client-visible summary를 계산한다.
5. `load-chat.mjs`는 기존 summary 필드와 함께 `takeoverDelivery` 객체를 JSON으로 출력한다.
6. takeover smoke는 load summary를 parse한 뒤 최종 JSON에 `takeoverDelivery`를 포함한다.

## 4. 판정 기준

| 경로 | raw inversion | duplicate replay | first-seen late delivery | client-visible result | 판정 |
| --- | --- | --- | --- | --- | --- |
| steady-state | 0이어야 함 | 0이어야 함 | 0이어야 함 | 정렬되어야 함 | raw 실패 시 release fail |
| owner kill/takeover | diagnostic으로 기록 | client-visible 통과 시 warning | 1건 이상이면 release fail | dedupe/sort 결과 유효해야 함 | client-visible 기준 |

owner kill/takeover 경로에서 `releaseBlocking`은 다음 조건 중 하나라도 만족하면 `true`다.

- viewer 중 하나라도 first-seen late delivery가 있다.
- viewer 중 하나라도 numeric `roomSeq` 또는 stable `messageId`가 없는 메시지를 받았다.
- dedupe 후 `roomSeq` 정렬 과정에서 같은 `roomSeq`에 서로 다른 `messageId`가 충돌한다.
- dedupe 후 unique message 수가 minimum receive ratio 기준에 미달한다.

## 5. 파일 책임

| 파일 | 책임 |
| --- | --- |
| `scripts/lib/loadChatPlan.mjs` | CLI option parsing, raw order assertion, takeover delivery summary 계산 |
| `scripts/lib/loadChatPlan.test.mjs` | summary 계산과 CLI option contract 테스트 |
| `scripts/load-chat.mjs` | takeover summary option을 받아 JSON summary에 포함 |
| `scripts/lib/phase6TakeoverSmokePlan.mjs` | takeover smoke가 load-chat에 summary option을 전달하고 최종 JSON을 안정적으로 parse |
| `scripts/lib/phase6TakeoverSmokePlan.test.mjs` | load arg mapping과 summary passthrough 테스트 |
| `scripts/phase6-fanout-takeover-smoke.mjs` | 최종 takeover smoke JSON에 delivery summary 포함 |
| `docs/phase7_slices.md` | 슬라이스 상태와 문서 링크 갱신 |

## 6. 테스트 전략

- `summarizeTakeoverDelivery()` 단위 테스트를 먼저 작성한다.
- duplicate replay는 raw inversion으로 기록하되 release blocking으로 보지 않는지 검증한다.
- first-seen late delivery는 release blocking으로 보는지 검증한다.
- missing `messageId` 또는 missing numeric `roomSeq`는 release blocking으로 보는지 검증한다.
- `parseLoadChatArgs()`가 takeover summary option을 파싱하는지 검증한다.
- `buildLoadChatArgs()`가 takeover smoke에서 summary option을 전달하는지 검증한다.
- `parseLoadChatJson()`이 nested summary 객체를 포함한 JSON을 안정적으로 parse하는지 유지 검증한다.
- Node contract tests와 syntax check를 실행한다.
- Compose가 실행 중이면 실제 takeover smoke를 1회 실행한다.

## 7. 복잡도

- viewer별 summary 계산 시간 복잡도: `O(M)`
- 전체 summary 계산 시간 복잡도: `O(V * Mv)`, 합산하면 `O(T)`
- 공간 복잡도: `O(U)`

여기서 `M`은 viewer 하나가 받은 메시지 수, `V`는 viewer 수, `Mv`는 viewer별 메시지 수, `T`는 모든 viewer가 받은 raw 메시지 수, `U`는 viewer별 고유 `messageId` 수다.

## 8. 주의사항

> - raw delivery 역전을 모두 성공으로 취급하면 안 된다. owner kill/takeover 경로에서만 diagnostic으로 완화한다.
> - duplicate replay는 client-visible 결과가 통과할 때만 warning으로 둘 수 있다. 중복 수와 비율은 summary에 남겨야 한다.
> - first-seen late delivery는 사용자가 최신 메시지를 본 뒤 과거 메시지를 처음 보게 되는 경우라 release blocking으로 본다.
> - smoke runner의 client-visible summary는 실제 UI 구현이 아니라 release gate용 시뮬레이션이다. 실제 client gap fill UX 검증은 후속 reconnect/load 슬라이스에서 다룬다.
> - `messageId`와 `roomSeq`가 없는 payload는 판정할 수 없으므로 release blocking으로 둔다.

## 9. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| raw diagnostic + client-visible gate | flake 원인 분리가 쉽고 기존 Phase 7 기준과 일치한다 | summary 계산 로직이 늘어난다 | 선택 |
| raw 역전 즉시 실패 유지 | 구현이 가장 단순하다 | duplicate replay와 사용자 화면 흔들림을 구분하지 못한다 | 사용하지 않음 |
| backend fanout 알고리즘 먼저 수정 | 근본 원인을 바로 줄일 수 있다 | 관측 기준 없이 수정하면 회귀 검증이 흐려진다 | 후속 필요 시 검토 |

## 10. 후속 질문

- duplicate replay 허용 한도를 절대 개수로 둘 것인가, 비율로 둘 것인가?
- 후속 슬라이스에서 actual client gap fill 검증을 reconnect synthetic load test에 포함할 것인가?
- `chat.fanout.owner.takeovers` metric semantics 확정을 이 summary 결과와 연결할 것인가?
