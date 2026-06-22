# Phase 7 Fanout Takeover Delivery Summary

이 문서는 owner kill takeover smoke가 raw delivery diagnostic과 client-visible release gate를 어떻게 분리하는지 설명한다.

## 1. 실행

```bash
node scripts/phase6-fanout-takeover-smoke.mjs \
  --room phase7-takeover-summary \
  --viewers 3 \
  --messages-per-sec 20 \
  --duration 20 \
  --kill-after 5 \
  --drain-wait 12
```

Nginx stale upstream synthetic check까지 restore 이후 함께 실행하려면 기존 Phase 7 opt-in 옵션을 추가한다.

```bash
node scripts/phase6-fanout-takeover-smoke.mjs \
  --room phase7-takeover-summary \
  --viewers 3 \
  --messages-per-sec 20 \
  --duration 20 \
  --kill-after 5 \
  --drain-wait 12 \
  --verify-routing-after-restore \
  --routing-check-base-url http://localhost \
  --routing-check-admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

## 2. Summary 필드

| 필드 | 의미 |
| --- | --- |
| `takeoverDelivery.releaseBlocking` | client-visible release gate 실패 여부 |
| `takeoverDelivery.aggregate.rawInversionCount` | raw 수신 순서에서 `roomSeq`가 뒤로 간 횟수 |
| `takeoverDelivery.aggregate.duplicateReplayCount` | 이미 본 `messageId`가 다시 온 횟수 |
| `takeoverDelivery.aggregate.firstSeenLateDeliveryCount` | 처음 보는 낮은 `roomSeq`가 뒤늦게 온 횟수 |
| `takeoverDelivery.aggregate.missingMessageIdCount` | `messageId`가 없어 판정할 수 없는 payload 수 |
| `takeoverDelivery.aggregate.missingRoomSeqCount` | numeric `roomSeq`가 없어 판정할 수 없는 payload 수 |
| `takeoverDelivery.aggregate.releaseBlockingViewerCount` | release blocking viewer 수 |
| `takeoverDelivery.viewers[].clientVisible.uniqueCount` | `messageId` dedupe 후 남은 메시지 수 |
| `takeoverDelivery.viewers[].clientVisible.duplicateCount` | client-visible 결과에서 제거된 중복 수 |
| `takeoverDelivery.viewers[].clientVisible.minReceivedSatisfied` | unique message 수가 최소 수신 기준을 만족하는지 |

## 3. 판정

- `releaseBlocking=false`: duplicate replay 같은 raw diagnostic은 있을 수 있지만 client-visible 결과는 안정적이다.
- `releaseBlocking=true`: first-seen late delivery, 판정 불가 payload, `roomSeq` 충돌, unique message 부족 중 하나가 발생했다.
- steady-state load에서는 `--assert-room-seq-order`를 계속 사용하고, raw `roomSeq` 역전을 실패로 본다.

## 4. 복잡도

- summary 계산 시간 복잡도: `O(T)`
- summary 계산 공간 복잡도: `O(U)`

여기서 `T`는 모든 viewer가 받은 raw 메시지 수이고, `U`는 viewer별 고유 `messageId` 수다.

## 5. 주의사항

> - owner kill/takeover 경로에서만 raw inversion을 diagnostic으로 완화한다.
> - duplicate replay는 client-visible 결과가 통과할 때만 warning으로 해석한다.
> - first-seen late delivery는 사용자가 최신 메시지를 본 뒤 과거 메시지를 처음 보게 되는 경우라 release blocking으로 본다.
> - smoke runner의 client-visible summary는 실제 UI 구현이 아니라 release gate용 시뮬레이션이다.
> - duplicate replay 허용 한도는 아직 수치 gate로 고정하지 않고 summary 수치로 남긴다.

## 6. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| client-visible gate | takeover flake를 사용자 영향 기준으로 분류한다 | summary 계산이 필요하다 | 기본 |
| raw order gate | 단순하다 | duplicate replay와 화면 흔들림을 구분하지 못한다 | 사용하지 않음 |
| fanout 알고리즘 선수정 | 중복 publish 원인을 줄일 수 있다 | 관측 기준 없이 회귀 여부를 판단하기 어렵다 | 후속 원인 분석 후 검토 |

## 7. 후속 질문

- duplicate replay 허용 한도를 어느 release gate에서 숫자로 고정할 것인가?
- actual client gap fill synthetic test를 reconnect load test에 포함할 것인가?
- `chat.fanout.owner.takeovers` metric semantics 확정을 이 summary 결과와 연결할 것인가?
