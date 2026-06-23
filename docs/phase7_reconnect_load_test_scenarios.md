# Phase 7 Reconnect Load Test Scenarios

이 문서는 Phase 7에서 WebSocket one-time ticket, reconnect storm, user/IP rate limit 원자성 기준을 검증하기 위한 synthetic reconnect/load test 시나리오를 정의한다.

## 0. 실행 가능한 Synthetic Runner

이번 슬라이스는 정상 reconnect baseline을 검증하는 runner를 제공한다.

```bash
node scripts/phase7-reconnect-load.mjs \
  --scenario baseline-reconnect \
  --clients 3 \
  --reconnects-per-client 2 \
  --cohort synthetic \
  --reason network_flap
```

환경 변수:

```text
CHAT_HTTP_URL=http://localhost/api
CHAT_WS_URL=ws://localhost/api/ws/chat
CHAT_PHASE7_RECONNECT_TIMEOUT_MS=15000
```

주요 옵션:

| 옵션 | 기본값 | 의미 |
| --- | --- | --- |
| `--clients` | `3` | synthetic client 수 |
| `--reconnects-per-client` | `2` | client당 reconnect 시도 수 |
| `--cohort` | `synthetic` | `direct`, `nat_proxy`, `mobile_carrier`, `synthetic` 중 하나 |
| `--reason` | `network_flap` | `network_flap`, `gateway_restart`, `gateway_kill`, `deploy`, `unknown` 중 하나 |
| `--min-ticket-issue-success-ratio` | `0.999` | ticket 발급 성공률 gate |
| `--min-handshake-success-ratio` | `0.999` | ticket 발급 후 handshake 성공률 gate |
| `--max-rate-limit-failure-ratio` | `0.001` | 정상 reconnect rate limit 실패율 gate |
| `--max-cohort-failure-ratio` | `0.003` | cohort별 실패율 gate |

Runner는 source IP를 직접 조작하지 않는다. `cohort`는 summary 분류용 bounded label이며, 실제 NAT/proxy/mobile carrier 재현은 별도 네트워크 구성에서 실행해야 한다.

`POST /ws-tickets`의 `429` 응답이 원인 body를 제공하지 않으면 runner는 세부 원인을 `rate_limited_user`나 `rate_limited_ip`로 나누지 않고 generic `rate_limited`로 분류한다. user/IP 세부 원인은 같은 시간대의 `chat.websocket.ticket.events` 서버 metric을 함께 확인한다.

## 1. 목표

- 정상 reconnect ticket 발급 성공률을 rolling 15분 기준으로 측정한다.
- user/IP ticket issue rate limit이 정상 reconnect UX를 해치지 않는지 확인한다.
- NAT, corporate proxy, mobile carrier 같은 동일 IP cohort에서 IP rate limit 영향도를 측정한다.
- 현재 단일 key Lua script + fail-closed 방식으로 충분한지, multi-key 완전 원자성이 필요한지 판단한다.
- abuse traffic과 정상 reconnect traffic을 분리해 release gate를 계산한다.

## 2. 정상 Reconnect 정의

정상 reconnect 시도는 다음 조건을 모두 만족한다.

- 유효한 session token을 가진 기존 사용자다.
- 이전 WebSocket 연결이 정상적인 네트워크 흔들림, Gateway 재시작, 배포, idle timeout, synthetic chaos로 끊겼다.
- reconnect 직전에 새 WebSocket one-time ticket을 발급받는다.
- invalid session, malformed ticket, token brute force, 비정상 고빈도 자동화는 제외한다.
- 동일 사용자가 rate limit을 의도적으로 소진하는 abuse 시나리오는 제외한다.

정상 reconnect 실패율:

```text
normalReconnectFailureRate =
  normalReconnectTicketIssueFailures / normalReconnectTicketIssueAttempts
```

rate limit으로 인한 정상 reconnect 실패율:

```text
normalReconnectRateLimitFailureRate =
  normalReconnectTicketIssueRateLimited / normalReconnectTicketIssueAttempts
```

여기서 `normalReconnectTicketIssueRateLimited`는 `rate_limited_user`와 `rate_limited_ip`를 포함한다.

## 3. Release Gate 기준

| 기준 | 값 | 판단 |
| --- | --- | --- |
| 정상 reconnect ticket 발급 성공률 | rolling 15분 `99.9%` 이상 | 만족 시 현재 단일 key Lua script + fail-closed 유지 |
| rate limit으로 인한 정상 reconnect 실패율 | `0.1%` 이하 | 만족 시 현재 정책 유지 |
| NAT/proxy/mobile carrier cohort p95 실패율 | `0.3%` 이하 | 만족 시 cohort별 정책 불필요 |
| 전체 정상 reconnect 실패율 | `0.1%` 초과 `0.5%` 이하 | limit/window/burst 튜닝 우선 |
| cohort p95 실패율 | `0.3%` 초과 `1.0%` 이하 | cohort별 limit/window/burst 튜닝 우선 |
| 전체 정상 reconnect 실패율 | 정책 튜닝 후 `0.5%` 초과가 rolling 15분 2회 이상 반복 | multi-key 원자 처리 또는 별도 rate limit service 검토 |
| cohort p95 실패율 | 정책 튜닝 후 `1.0%` 초과가 rolling 15분 2회 이상 반복 | multi-key 원자 처리 또는 cohort별 정책 분리 검토 |
| IP 거부 후 user counter 보수적 소모 추정 실패율 | 정상 reconnect 시도 중 `0.2%` 초과가 rolling 15분 2회 이상 반복 | user/IP 완전 원자성 검토 |

## 4. 시나리오

### 4.1 Baseline Reconnect

목적:

- 부하가 낮은 상태에서 정상 reconnect 기준선을 측정한다.

조건:

- 1,000 concurrent WebSocket clients
- 각 client는 15분 동안 1~3회 reconnect
- 각 reconnect 직전에 새 ticket 발급
- IP는 충분히 분산

통과 기준:

- 정상 reconnect ticket 발급 성공률 `99.9%` 이상
- rate limit으로 인한 정상 reconnect 실패율 `0.1%` 이하
- ticket issue latency p95가 운영 목표 이하

### 4.2 Gateway Rolling Restart

목적:

- 배포 또는 Gateway 재시작 중 reconnect storm을 검증한다.

조건:

- 10,000 concurrent WebSocket clients
- Gateway instance를 25%씩 순차 재시작
- client reconnect는 exponential backoff와 jitter 적용
- 각 reconnect는 새 ticket 발급

통과 기준:

- rolling 15분 정상 reconnect ticket 발급 성공률 `99.9%` 이상
- rate limit 실패율 `0.1%` 이하
- reconnect storm 중 ticket issue latency p95/p99가 dashboard에 표시됨

### 4.3 Gateway Hard Kill

목적:

- 예기치 않은 Gateway 장애에서 reconnect burst를 검증한다.

조건:

- 10,000 concurrent WebSocket clients
- Gateway 1개 또는 50%를 즉시 kill
- client reconnect backoff는 production 기본값 사용

통과 기준:

- 정상 reconnect ticket 발급 성공률 `99.9%` 이상
- Redis Lua script failure 없음
- Redis 장애가 없다면 rate limit 실패율 `0.1%` 이하

### 4.4 NAT / Corporate Proxy Cohort

목적:

- 다수 정상 사용자가 같은 source IP로 보이는 환경에서 IP rate limit 영향을 측정한다.

조건:

- cohort A: 100 users / 1 IP
- cohort B: 500 users / 1 IP
- cohort C: 1,000 users / 1 IP
- cohort별 reconnect burst를 15분 동안 반복

통과 기준:

- cohort p95 정상 reconnect rate limit 실패율 `0.3%` 이하
- `0.3%` 초과 `1.0%` 이하이면 IP limit/window/burst 튜닝
- `1.0%` 초과가 rolling 15분 2회 이상 반복되면 cohort별 정책 또는 완전 원자성 검토

### 4.5 Mobile Carrier Flap

목적:

- 모바일 네트워크처럼 짧은 연결 끊김과 handshake 지연이 반복되는 환경을 검증한다.

조건:

- 5,000 concurrent clients
- 10~30초 간격의 random disconnect
- 100~1,000ms latency jitter
- 일부 ticket은 만료 직전 WebSocket handshake 시도

통과 기준:

- 정상 reconnect ticket 발급 성공률 `99.9%` 이상
- ticket TTL 관련 실패와 rate limit 실패가 분리 관측됨
- TTL 문제는 TTL/window 튜닝 대상으로 분류하고, 완전 원자성 판단에는 직접 포함하지 않음

### 4.6 Redis Latency / Script Failure

목적:

- Redis 지연 또는 Lua script 실패 시 fail-closed와 alert를 확인한다.

조건:

- Redis latency 주입
- Lua script timeout 또는 Redis restart chaos
- 정상 reconnect와 abuse traffic을 분리해 발생

통과 기준:

- `chat.websocket.ticket.rate_limit.script.failures`가 증가하고 alert 후보 조건을 만족
- 장애 구간은 정상 reconnect failure gate 계산에서 별도 라벨로 분리
- 장애 종료 후 reconnect 성공률이 회복됨

### 4.7 Abuse Mixed Traffic

목적:

- 정상 reconnect traffic과 abuse traffic이 섞여도 정상 사용자 기준을 분리 계산할 수 있는지 확인한다.

조건:

- 정상 reconnect traffic 90%
- invalid session, malformed ticket, 고빈도 ticket issue request 10%
- 동일 IP abuse burst 포함

통과 기준:

- abuse traffic은 정상 reconnect 실패율 계산에서 제외
- abuse request는 fail-closed 또는 rate limited
- 정상 reconnect rate limit 실패율이 기준 이하

## 5. 현재 Micrometer Metric 충분성 점검

현재 metric만으로 확인 가능한 것:

- `chat.websocket.ticket.events`로 전체 ticket issue/consume event count 확인
- `chat.websocket.ticket.issue.latency`로 outcome별 ticket issue latency 확인
- `chat.websocket.ticket.rate_limit.script.failures`로 Redis Lua script failure 확인
- `http.server.requests`로 `/api/ws-tickets` HTTP latency/status 확인

현재 metric만으로 부족한 것:

- ticket issue가 initial connect인지 reconnect인지 구분할 수 없다.
- 정상 reconnect와 abuse/invalid/malformed 요청을 metric 차원에서 분리할 수 없다.
- NAT/proxy/mobile carrier cohort별 정상 reconnect 실패율을 계산할 수 없다.
- IP limit 거부 후 user counter가 보수적으로 소모되어 발생한 실패인지 직접 식별할 수 없다.
- client가 ticket 발급에는 성공했지만 WebSocket handshake에서 실패한 비율을 같은 denominator로 묶기 어렵다.

결론:

현재 Micrometer metric은 전체 ticket 보안 경로의 health와 latency를 보기에는 충분하지만, Phase 7 release gate인 “정상 reconnect 실패율”을 계산하기에는 부족하다. low-cardinality context를 가진 추가 event metric과 synthetic test runner 결과가 필요하다.

## 6. 추가 Metric 제안

아래 3개 metric은 현재 구현되지 않은 **TODO**다. Phase 7 synthetic reconnect/load test를 구현하기 전 또는 같은 작업에서 추가한다. 구현 전까지는 synthetic test runner summary를 기준 데이터로 사용하고, 기존 Micrometer metric은 backend health와 원인 분석에 사용한다.

- `chat.websocket.ticket.issue.outcomes`
- `chat.websocket.reconnect.attempts`
- `chat.websocket.ticket.rate_limit.sequential_overcount.suspected`

### 6.1 Ticket Issue Outcome Metric

```text
chat.websocket.ticket.issue.outcomes
```

Type:

- Counter

Tags:

- `intent=initial|reconnect|synthetic|unknown`
- `outcome=success|rate_limited_user|rate_limited_ip|failure|collision`
- `cohort=direct|nat_proxy|mobile_carrier|synthetic|unknown`
- `source=client|synthetic|server`

금지 tag:

- `userId`
- raw IP
- session token
- WebSocket ticket
- messageId

### 6.2 Reconnect Attempt Metric

```text
chat.websocket.reconnect.attempts
```

Type:

- Counter

Tags:

- `source=client|synthetic`
- `reason=network_flap|gateway_restart|gateway_kill|deploy|unknown`
- `outcome=ticket_issued|ticket_rate_limited|handshake_success|handshake_failure`
- `cohort=direct|nat_proxy|mobile_carrier|synthetic|unknown`

### 6.3 Sequential Rate Limit Overcount Suspect Metric

```text
chat.websocket.ticket.rate_limit.sequential_overcount.suspected
```

Type:

- Counter

증가 조건:

- user rate limit script는 통과했다.
- 같은 issue attempt에서 IP rate limit script가 거부했다.
- 결과적으로 ticket은 발급되지 않았지만 user counter는 이미 증가했다.

Tags:

- `cohort=direct|nat_proxy|mobile_carrier|synthetic|unknown`

이 metric은 완전 원자성 도입 여부를 판단하는 보조 신호다. 값이 증가한다고 곧바로 구조 변경을 의미하지 않고, 정상 reconnect 실패율 기준과 함께 판단한다.

## 7. Synthetic Test Runner Output

부하 테스트 스크립트는 서버 metric과 별개로 다음 JSON summary를 남긴다.

```json
{
  "ok": true,
  "scenario": "baseline-reconnect",
  "durationSeconds": 12,
  "normalReconnectAttempts": 6,
  "normalReconnectTicketIssued": 6,
  "normalReconnectRateLimited": 0,
  "normalReconnectHandshakeSucceeded": 6,
  "normalReconnectHandshakeFailed": 0,
  "rates": {
    "ticketIssueSuccessRate": 1,
    "rateLimitFailureRate": 0,
    "handshakeSuccessRate": 1
  },
  "cohorts": {
    "synthetic": {
      "attempts": 6,
      "ticketIssued": 6,
      "rateLimited": 0,
      "handshakeSucceeded": 6,
      "handshakeFailed": 0,
      "failureRate": 0,
      "rateLimitFailureRate": 0
    }
  },
  "failedGates": []
}
```

이 summary는 Phase 7 release gate 계산의 기준 데이터로 사용한다. 서버 Micrometer metric은 같은 시간대의 backend health와 원인 분석에 사용한다.

`ok=false`일 때 `failedGates`는 다음 이름을 포함할 수 있다.

| Gate | 의미 |
| --- | --- |
| `ticket_issue_success_ratio` | 정상 reconnect ticket 발급 성공률이 기준 미만 |
| `handshake_success_ratio` | ticket 발급 후 WebSocket handshake 성공률이 기준 미만 |
| `rate_limit_failure_ratio` | 정상 reconnect rate limit 실패율이 기준 초과 |
| `cohort_failure_ratio:<cohort>` | cohort별 실패율이 기준 초과 |

## 8. 복잡도

- test runner 이벤트 집계 시간 복잡도: `O(N)`
- test runner summary 공간 복잡도: `O(C + S)`
- 서버 metric 기록 시간 복잡도: `O(1)`
- 서버 metric 공간 복잡도: tag cardinality에 비례

여기서 `N`은 test event 수, `C`는 cohort 수, `S`는 scenario 수다.

## 9. 주의사항

> - 정상 reconnect 실패율에는 abuse, invalid session, malformed ticket, 수동 Redis chaos 구간을 섞지 않는다.
> - cohort tag는 bounded enum으로 유지한다. IP나 ASN을 직접 metric tag로 넣지 않는다.
> - synthetic test 기준을 production 사용자 지표와 섞어 보지 않는다. dashboard에서는 source tag로 분리한다.
> - 완전 원자성은 UX 지표가 기준을 넘고 정책 튜닝으로도 해결되지 않을 때 검토한다.

## 10. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 현재 metric만 사용 | 구현 추가 없음 | 정상 reconnect 실패율을 정확히 계산할 수 없음 | Phase 7 기준으로 부족 |
| 서버 metric에 low-cardinality context 추가 | 운영 dashboard 계산 가능 | 클라이언트/테스트가 intent/cohort를 전달해야 함 | 권장 |
| test runner summary만 사용 | 시나리오별 gate 계산이 명확함 | production dashboard와 직접 연결이 약함 | 보조 |
| tracing 기반 계산 | 개별 흐름 분석에 강함 | sampling과 비용 문제, gate 계산에는 불안정 | 장애 분석용 |
