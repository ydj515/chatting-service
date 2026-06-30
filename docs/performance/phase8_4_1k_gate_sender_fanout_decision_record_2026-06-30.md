# Phase 8.4 1k Gate Sender/Fanout 판단 기록

- 작성일: 2026-06-30
- 상태: 판단 보류
- 대상: Phase 8.4 staged release gate의 `1k` stage
- 관련 문서:
  - `production-readiness-assessment-2026-06-28.html`
  - `docs/superpowers/specs/2026-06-29-phase8-4-staged-release-gate-design.md`
  - `docs/superpowers/plans/2026-06-29-phase8-4-staged-release-gate.md`

## 1. 목적

이 문서는 `1k` gate 실패 이후 나눈 판단을 기록한다. 핵심 목적은 다음 두 가지다.

- 현재 결과를 production-ready로 만족해도 되는지 판단 기준을 남긴다.
- sender 경로와 fanout/viewer 수신 경로를 분리하는 설계 변경이 필요한지, 또는 아직 과한 변경인지 추후 판단할 근거를 남긴다.

현재 결론은 다음과 같다.

> `1k` gate는 production-ready 판정으로는 만족하기 어렵다.  
> 다만 production gateway 구조를 즉시 sender용과 fanout용으로 나누는 것은 아직 성급할 수 있다.  
> 다음 단계는 production 구조 변경이 아니라, load runner 또는 실행 구조에서 sender 계측과 viewer/fanout 수신 계측을 분리해 병목 위치를 확정하는 것이다.

## 2. 조건 정리

### 현재 gate 조건

- 기본 staged gate는 `1k -> 3k -> 5k -> 7k -> 10k` 순서로 실행한다.
- `1k` stage는 `1000 viewers`, `1000 messages/sec`, `60s` 조건이다.
- 총 전송 목표는 `60,000` messages다.
- sender accepted 기준은 `99%` 이상으로 두는 방향이다.
- viewer 수신 기준은 sender accepted 수를 기준으로 판단하는 방향이다.

### 대화 중 확인한 관찰값

아래 값은 대화 시점의 로컬 재실행 관찰값이다. 정식 장기 보존 artifact로 고정된 값은 아니므로, 다음 재현에서는 stdout JSON과 stderr를 별도 artifact로 남겨야 한다.

`latest fast runner`는 이 PR 브랜치의 `scripts/load-chat.mjs` count-only summary 경로에서 `1000 viewers`, `1000 messages/sec`, `60s`, `16 senders`, hot room shard seed 조건으로 실행한 baseline을 뜻한다.

| 조건 | 관찰 결과 | 해석 |
| --- | ---: | --- |
| 초기 `1k` 실행 | viewer 0이 `10839/60000` 수준만 수신 | full fanout 조건에서 명백히 목표 미달 |
| single sender accepted | `13788/60000` 수준 | sender 전송 성공 자체가 낮음 |
| `16` senders | `23196/60000` 수준 | sender 수 증가만으로 해결되지 않음 |
| `16` senders + drain wait | `30364/60000` 수준 | drain 대기 보강 후에도 `99%`와 거리가 큼 |
| `1 viewer` + `16` senders | `58788/60000` 수준 | viewer fanout 부하가 줄면 sender accepted가 크게 회복됨 |
| outbound budget 확대 | `18392/60000` 수준 | queue/thread/buffer 확대만으로 안정 개선 안 됨 |
| ACK priority 적용 후 | `14882/60000` 수준 | ACK 우선순위만으로는 병목이 풀리지 않음 |
| `latest fast runner` baseline | `21690/60000` 수준 | 이 PR의 count-only/ACK tracking 경로에서도 full fanout 조건은 목표 미달 |

### 현재까지의 중요한 신호

- DB 저장 수는 sender accepted 수와 대체로 유사하게 관찰됐다.
- 따라서 단순히 viewer collector가 느리거나 수신 카운트가 틀린 문제만으로 보기는 어렵다.
- `1 viewer` 조건에서는 accepted가 크게 회복됐다.
- `1000 viewers` 조건에서는 accepted 자체가 크게 떨어졌다.
- 작은 튜닝을 여러 차례 적용했지만 결과가 안정적으로 개선되지 않았다.

## 3. 현재 판단

### 만족 여부

`1k` gate를 production-ready 판정으로 만족하기는 어렵다.

이유:

- 목표가 `60,000` messages 중 `99%` accepted라면 최소 `59,400` accepted가 필요하다.
- full `1000 viewers` 조건에서는 관찰값이 그 기준보다 크게 낮다.
- viewer fanout이 늘어날수록 sender accepted도 같이 낮아지는 현상이 보인다.
- 이 상태로 `3k`, `5k`, `7k`, `10k`로 올리면 같은 병목이 더 커질 가능성이 높다.

### 오버엔지니어링 여부

production gateway를 즉시 sender gateway와 fanout gateway로 물리적으로 분리하는 것은 아직 오버일 수 있다.

이유:

- 현재 결과만으로는 병목이 load runner 내부 경합인지, gateway 내부 자원 경합인지 확정되지 않았다.
- load runner가 같은 프로세스에서 sender ACK 계측과 1000 viewer 수신 처리를 동시에 수행하므로, 테스트 도구 자체가 병목에 섞였을 가능성이 남아 있다.
- 따라서 먼저 계측 경로를 분리해 관찰해야 한다.

## 4. Production gateway 구조를 sender용과 fanout용으로 나눈다는 의미

여기서 "production gateway 구조를 sender용과 fanout용으로 나눈다"는 말은 단순히 코드 안에서 함수를 나누는 수준이 아니다. 운영 트래픽을 받는 WebSocket gateway를 역할별로 분리해, 메시지를 보내는 연결과 메시지를 대량 수신하는 연결이 서로의 자원을 덜 잠식하게 만드는 구조 변경을 뜻한다.

### 현재 구조의 의미

현재 구조에서는 같은 WebSocket gateway pool이 다음 책임을 함께 가진다.

- sender WebSocket 연결을 받는다.
- sender가 보낸 `CHAT_MESSAGE_SEND` 같은 inbound frame을 처리한다.
- admission, room policy, sequence, stream ingest, persistence 경로로 메시지를 넘긴다.
- sender에게 `MESSAGE_ACCEPTED` 또는 error ACK를 보낸다.
- 같은 room의 viewer들에게 fanout message를 outbound로 전송한다.
- 느린 viewer, 큰 outbound queue, WebSocket write 지연을 같은 gateway 자원 안에서 처리한다.

이 구조에서는 hot room에 viewer가 많이 붙으면 outbound fanout 작업이 gateway의 thread, executor, socket buffer, event loop, outbound queue, GC, CPU를 많이 사용한다. 그 결과 sender inbound 처리나 ACK 전송도 같은 자원 경합에 휘말릴 수 있다.

### 분리 구조의 의미

sender용 gateway와 fanout용 gateway를 나눈다는 것은 대략 다음처럼 역할을 분리하는 것이다.

| 역할 | 주요 책임 | 보호하려는 경로 |
| --- | --- | --- |
| sender gateway | sender 연결, 메시지 수락, admission, stream ingest 요청, accepted/error ACK | inbound accept path, ACK/control path |
| fanout gateway | viewer 연결, room fanout 구독, outbound queue, WebSocket write, slow client 처리 | outbound fanout path, viewer delivery path |

이렇게 나누면 sender gateway는 대량 viewer outbound write에 직접 눌리지 않는다. 반대로 fanout gateway는 sender ACK 지연을 크게 신경 쓰지 않고 viewer delivery, queue, slow client 정책에 집중할 수 있다.

### 운영상 포함되는 변경

이 변경은 보통 다음 항목을 함께 요구한다.

- nginx 또는 ingress에서 sender 연결과 viewer 연결을 서로 다른 upstream으로 라우팅한다.
- sender gateway pool과 fanout gateway pool을 별도 service, deployment, compose scale 단위로 운영한다.
- sender gateway는 inbound/ACK 지표를, fanout gateway는 outbound/write/queue 지표를 따로 가진다.
- fanout gateway가 어떤 room을 구독할지 결정하는 room routing 또는 fanout subscription 정책이 필요하다.
- sender가 수락한 메시지를 fanout gateway까지 전달하는 Redis Streams, pub/sub, worker, internal event 경로의 책임 경계를 명확히 해야 한다.

### 왜 큰 변경인가

이 변경은 단순한 성능 튜닝보다 크다.

- 연결 라우팅 규칙이 바뀐다.
- 배포 단위와 scale 단위가 바뀐다.
- metric과 alert 기준을 역할별로 다시 나눠야 한다.
- 장애 격리 방식이 바뀐다.
- sender와 viewer가 같은 room에 붙더라도 서로 다른 gateway pool에 있을 수 있으므로 room membership, presence, fanout subscription 정합성을 더 명확히 해야 한다.
- Docker Compose에서는 nginx upstream 분리와 scale 정책이 필요하고, Kubernetes에서는 별도 Deployment/Service/HPA 설계가 필요하다.

따라서 지금 문서에서 "즉시 나누는 것은 성급할 수 있다"고 한 의미는, 이 변경이 효과가 없다는 뜻이 아니다. 현재 증거만으로는 load runner 내부 경합과 gateway 내부 경합이 아직 분리되지 않았으므로, 이 정도로 큰 production 구조 변경을 바로 승인하기에는 근거가 부족하다는 뜻이다.

### 작은 단계와 큰 단계의 구분

| 단계 | 의미 | production 영향 | 현재 판단 |
| --- | --- | --- | --- |
| load runner sender/viewer 분리 | 테스트 도구에서 sender 계측과 viewer 수신 계측을 분리 | 없음 또는 매우 작음 | 먼저 수행 |
| gateway 내부 lane 분리 | 같은 gateway 안에서 inbound/ACK executor와 outbound executor를 더 명확히 분리 | 중간 | 증거 확보 후 검토 |
| gateway pool 역할 분리 | sender gateway pool과 fanout gateway pool을 별도 배포/라우팅 단위로 분리 | 큼 | 아직 보류 |
| room-aware hot room 전용 pool | hot room만 별도 gateway/fanout pool로 격리 | 큼 | 3k/5k 이후 검토 |

## 5. 권장 다음 단계

다음 작업은 production 구조 변경이 아니라, 원인 분리용 계측 변경으로 제한한다.

1. load runner에 sender-only 모드 또는 sender 전용 실행 경로를 둔다.
2. viewer/fanout 수신 계측은 별도 모드 또는 별도 프로세스로 분리한다.
3. 같은 `1k` 조건에서 재실행한다.
4. sender accepted, viewer received, DB stored count를 각각 비교한다.
5. 결과에 따라 gateway inbound 또는 outbound 중 어디를 좁힐지 결정한다.

## 6. 판단 매트릭스

| 다음 실험 결과 | 해석 | 후속 결정 |
| --- | --- | --- |
| sender/viewer 분리 후 sender accepted가 `99%` 근처로 회복 | load runner 내부 경합 또는 계측 비용 가능성 큼 | production gateway 구조 변경 보류 |
| sender/viewer 분리 후에도 sender accepted가 낮음 | gateway 내부에서 inbound/control/ACK 경로가 outbound fanout 부하에 눌릴 가능성 큼 | gateway inbound/outbound lane 분리 설계 검토 |
| sender accepted는 높고 viewer received만 낮음 | inbound는 비교적 정상, outbound fanout이 주 병목 | fanout batching, shard, outbound queue, slow client 정책 집중 |
| DB stored가 accepted보다 크게 낮음 | persistence 또는 stream ingest 병목 | gateway보다 persistence/Redis/Postgres 경로 우선 조사 |
| DB stored는 높고 accepted만 낮음 | ACK 전송 또는 sender ACK 계측 병목 | ACK path와 sender 계측 path 우선 조사 |

## 7. 지금 멈춰야 할 것

아래 작업은 새 증거 없이 계속 진행하지 않는다.

- queue 크기, executor thread, send buffer만 반복해서 키우는 튜닝
- ACK priority 위에 추가 priority 규칙을 계속 얹는 방식
- production gateway를 바로 sender 전용과 fanout 전용으로 나누는 큰 구조 변경
- `1k` 기준을 조용히 낮추고 production-ready로 판정하는 것

## 8. 복잡도

- hot room fanout 비용은 메시지 수를 `M`, viewer 수를 `V`라고 할 때 대략 `O(M * V)`다.
- `1k` stage에서는 `M = 60,000`, `V = 1,000`이므로 viewer별 전송 관점의 최대 fanout 단위는 대략 `60,000,000`개다.
- sender/viewer 계측 분리는 이 fanout 총량을 줄이지 않는다.
- 대신 sender ingress/ACK 경로와 viewer outbound 수신 경로의 관측 비용을 분리해 병목 원인 탐색 비용을 줄인다.
- 공간 복잡도는 viewer별 outbound queue 한도를 `Q`라고 할 때 대략 `O(V * Q)`다.

## 9. 주의사항

> - 이 문서는 production 구조 변경을 승인하는 문서가 아니다.
> - 이 문서는 `1k` gate를 production-ready로 승인하는 문서도 아니다.
> - 현재 단계의 핵심은 설계 확정이 아니라 병목 위치 확정이다.
> - `1 viewer` 조건에서 거의 회복된 것은 강한 힌트지만, load runner 내부 경합과 gateway 내부 경합을 구분하려면 추가 분리 계측이 필요하다.
> - 다음 실험부터는 실행 command, stdout JSON, stderr, compose scale, env override를 artifact로 보존해야 한다.

## 10. 대안 비교

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 현재 결과를 만족하고 넘어감 | 추가 구현 없음 | `1k` production gate 의미가 약해지고 `3k+` 근거가 없음 | 비추천 |
| env/queue/thread 튜닝을 더 반복 | 구현이 작고 빠름 | 이미 효과가 불안정했고 원인 확정에 도움이 적음 | 비추천 |
| load runner sender/viewer 분리 | production 영향이 작고 원인 분리에 직접적 | 구현과 재실행 비용이 있음 | 추천 |
| gateway 내부 inbound/outbound lane 분리 | 실제 서버 경합을 줄일 수 있음 | 아직 원인 확정 전이라 과할 수 있음 | 보류 |
| sender gateway와 fanout gateway 역할 분리 | 자원 격리가 가장 명확함 | 라우팅, 배포, 운영 복잡도가 큼 | 원인 확정 후 검토 |

## 11. 후속 질문

- `1k` gate의 현재 상태를 "production-ready 실패"가 아니라 "병목 탐색 단계"로 명시할 것인가?
- sender-only 모드는 기존 `scripts/load-chat.mjs`에 옵션으로 둘 것인가, 별도 스크립트로 둘 것인가?
- viewer/fanout 수신 계측은 같은 머신의 별도 프로세스로 충분한가, 아니면 분산 load generator까지 고려해야 하는가?
- 다음 재실행 artifact의 표준 이름과 저장 위치를 `.artifacts/phase8-gate` 아래에 고정할 것인가?
