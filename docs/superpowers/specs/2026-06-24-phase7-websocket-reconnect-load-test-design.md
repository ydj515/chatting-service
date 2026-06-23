# Phase 7 WebSocket Reconnect Synthetic Load Test 설계서

- 작성일: 2026-06-24
- 슬라이스: WebSocket reconnect synthetic load test
- 상태: 설계 승인됨

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 2.5에서 WebSocket one-time ticket 기반 handshake가 도입되었다.
- Phase 7 기존 문서는 정상 reconnect ticket 발급 성공률 `99.9%` 이상, rate limit으로 인한 정상 reconnect 실패율 `0.1%` 이하, NAT/proxy/mobile carrier cohort p95 실패율 `0.3%` 이하를 release gate 후보로 정의했다.
- 기존 `scripts/load-chat.mjs`는 메시지 fanout 부하 검증에 초점이 있고, reconnect 직전 ticket 발급과 handshake 성공률을 독립 summary로 계산하지 않는다.
- 실제 gateway kill/restart chaos는 실행 환경에 영향을 주므로, 먼저 안전한 synthetic reconnect runner가 필요하다.
- 서버 metric은 아직 reconnect intent/cohort를 직접 구분하지 못하므로, 이번 슬라이스의 primary evidence는 runner JSON summary다.

### 목표

- 정상 reconnect synthetic runner를 추가한다.
- 각 reconnect 시도마다 새 WebSocket one-time ticket을 발급하고 raw WebSocket handshake를 수행한다.
- ticket issue success, rate limited failure, handshake success/failure를 같은 denominator로 집계한다.
- cohort tag를 bounded enum으로 유지한다.
- release gate 실패 원인을 `failedGates`로 분리한다.
- Phase 7 슬라이스 인덱스와 reconnect 운영 문서를 갱신한다.

### 비범위

- 서버 Micrometer metric 추가는 이번 슬라이스에서 구현하지 않는다.
- Gateway container kill/restart orchestration은 구현하지 않는다.
- abuse/invalid/malformed traffic generator는 구현하지 않는다.
- source IP를 실제로 조작하지 않는다. `cohort`는 synthetic summary 분류용 bounded label이다.

## 2. 해결 접근

### 선택한 접근

`scripts/phase7-reconnect-load.mjs`를 추가하고, 순수 계산은 `scripts/lib/phase7ReconnectLoadPlan.mjs`에 둔다.

Runner 흐름:

1. synthetic client 수만큼 사용자를 등록하고 로그인한다.
2. client별 reconnect attempt plan을 만든다.
3. 각 attempt 직전에 `POST /ws-tickets`로 새 one-time ticket을 발급한다.
4. 발급 성공 시 raw WebSocket handshake를 수행하고 즉시 닫는다.
5. 모든 attempt를 `summarizeReconnectAttempts()`로 집계한다.
6. JSON summary의 `ok`와 `failedGates`로 release gate 결과를 표현한다.

### 이유

- 실제 메시지 fanout과 분리되어 WebSocket ticket/reconnect 경로만 작게 검증할 수 있다.
- 기존 Node ESM script와 `node:test` 패턴을 따른다.
- Docker container를 직접 kill하지 않아 로컬 검증 기본값이 안전하다.
- 이후 gateway rolling restart, hard kill, mobile flap 시나리오가 같은 summary gate를 재사용할 수 있다.

## 3. 파일 책임

| 파일 | 책임 |
| --- | --- |
| `scripts/lib/phase7ReconnectLoadPlan.mjs` | CLI 옵션 파싱, attempt plan 생성, ticket/handshake summary gate 계산 |
| `scripts/lib/phase7ReconnectLoadPlan.test.mjs` | parser, gate summary, cohort failure, ticket failure classification 테스트 |
| `scripts/phase7-reconnect-load.mjs` | 사용자 등록/로그인, ticket 발급, raw WebSocket handshake 실행, JSON summary 출력 |
| `docs/phase7_reconnect_load_test_scenarios.md` | 실행 명령, summary 필드, 판정 기준 |
| `docs/phase7_slices.md` | 슬라이스 상태와 다음 후보 갱신 |

## 4. Summary 판정 기준

| Gate | 기본값 | 실패 조건 |
| --- | --- | --- |
| `ticket_issue_success_ratio` | `0.999` | `normalReconnectTicketIssued / normalReconnectAttempts`가 기준 미만 |
| `handshake_success_ratio` | `0.999` | `normalReconnectHandshakeSucceeded / normalReconnectTicketIssued`가 기준 미만 |
| `rate_limit_failure_ratio` | `0.001` | `normalReconnectRateLimited / normalReconnectAttempts`가 기준 초과 |
| `cohort_failure_ratio:<cohort>` | `0.003` | cohort별 `(attempts - handshakeSucceeded) / attempts`가 기준 초과 |

`ok=false`이면 `failedGates`에 실패한 gate 이름을 넣는다.

## 5. 테스트 전략

- `parseReconnectLoadArgs()`가 CLI 값을 기본값 위에 매핑하는지 검증한다.
- cohort/reason/ratio validation을 검증한다.
- attempt plan이 client 수와 reconnect 수의 곱으로 만들어지는지 검증한다.
- ticket issue와 handshake가 모두 성공하면 summary가 통과하는지 검증한다.
- rate limit 실패가 ticket success, rate limit, cohort gate를 실패시키는지 검증한다.
- ticket 발급 후 handshake 실패가 handshake와 cohort gate를 실패시키는지 검증한다.
- 스크립트 문법 검사를 실행한다.
- Compose 서비스가 준비된 환경에서는 작은 client 수로 runner를 1회 실행한다.

## 6. 복잡도

- attempt plan 생성 시간 복잡도: `O(C * R)`
- summary 계산 시간 복잡도: `O(N)`
- runner 네트워크 실행 시간 복잡도: `O(N)` 요청/handshake
- summary 공간 복잡도: `O(K)`

여기서 `C`는 client 수, `R`은 client당 reconnect 수, `N=C*R`, `K`는 cohort 수다.

## 7. 주의사항

> - 이 runner의 `cohort`는 source IP를 바꾸지 않는다. NAT/proxy/mobile carrier 영향을 실제로 재현하려면 별도 네트워크/프록시 구성이 필요하다.
> - 정상 reconnect 실패율에는 abuse, invalid session, malformed ticket을 섞지 않는다.
> - 기본 gate는 운영 목표를 반영하므로 작은 로컬 샘플에서는 1건 실패도 `ok=false`가 될 수 있다.
> - gateway kill/restart orchestration은 이 runner를 재사용하는 별도 chaos 슬라이스에서 다룬다.
> - one-time ticket은 attempt마다 새로 발급해야 하며, 재사용 ticket 성공을 reconnect 성공으로 세면 안 된다.
> - `POST /ws-tickets`의 `429` 응답 body가 비어 있으면 runner는 user/IP 세부 원인을 generic `rate_limited`로만 분류한다.

## 8. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| synthetic runner summary gate | 안전하게 reconnect 경로를 반복 검증하고 원인별 summary가 명확하다 | 실제 gateway 장애 주입은 별도 필요 | 선택 |
| 기존 `load-chat`에 옵션 추가 | 사용자/WS helper를 재사용할 수 있다 | fanout load와 reconnect gate 책임이 섞인다 | 제외 |
| 서버 metric 먼저 추가 | 운영 dashboard와 직접 연결된다 | client-side handshake denominator를 놓치기 쉽다 | 후속 |
| chaos orchestration 먼저 구현 | 실제 장애 대응성을 빠르게 본다 | 안전한 baseline runner 없이 실패 원인 분리가 어렵다 | 후속 |

## 9. 후속 질문

- gateway rolling restart/hard kill orchestration을 다음 chaos 슬라이스에 포함할 것인가?
- reconnect intent/cohort를 서버 metric tag로 받을 API contract를 추가할 것인가?
- NAT/proxy cohort를 실제 source IP 기반으로 재현할 테스트 인프라를 둘 것인가?
