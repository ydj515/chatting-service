# Observability Metrics

이 문서는 Phase 7 운영 검증과 릴리즈 게이트에서 dashboard와 alert에 연결할 metric 목록을 정리한다.

Reconnect load test 시나리오와 정상 reconnect 실패율 계산 기준은 [phase7_reconnect_load_test_scenarios.md](./phase7_reconnect_load_test_scenarios.md)를 따른다.

## 1. 목표

- hot room ingest, fan-out, 저장, 검색, WebSocket ticket 보안 경로를 한 화면에서 판단한다.
- 장애가 발생했을 때 어느 role이 병목인지 빠르게 좁힌다.
- release gate에서 p95/p99 latency, lag, failure rate를 수치로 확인한다.
- metric tag cardinality를 통제해 운영 비용과 Prometheus/TSDB 부하를 관리한다.

## 2. Cardinality 원칙

- metric tag에 `sessionToken`, WebSocket `ticket`, raw IP, message body를 넣지 않는다.
- `userId`, `sessionId`, `messageId`, `clientMessageId`는 metric tag로 쓰지 않는다.
- `roomId` tag는 전체 방에 무제한으로 붙이지 않는다. hot room top N, synthetic test room, 또는 `roomHeat=NORMAL|HOT|VERY_HOT|OVERLOAD` 같은 bounded tag를 우선 사용한다.
- exception class, outcome, role, scope, shard, heat 등 값의 종류가 제한된 tag만 기본 허용한다.
- 상세 추적이 필요하면 metric tag가 아니라 log/tracing span attribute로 보낸다.

> 운영 metric은 “집계 가능한 신호”여야 한다. 개별 사용자나 개별 메시지를 metric cardinality로 표현하면 부하 상황에서 observability 시스템이 먼저 병목이 될 수 있다.

## 3. Dashboard 섹션

### 3.1 WebSocket Ticket / Security

다음 3개 metric은 아직 구현되지 않은 **TODO**다. Phase 7 synthetic reconnect/load test를 구현하기 전 또는 함께 추가한다.

- `chat.websocket.ticket.issue.outcomes`
- `chat.websocket.ticket.rate_limit.sequential_overcount.suspected`
- `chat.websocket.reconnect.attempts`

| Metric | Type | Tags | 목적 |
| --- | --- | --- | --- |
| `chat.websocket.ticket.events` | Counter | `event` | ticket issue/consume 성공, 실패, 만료, malformed, rate limit 현황 |
| `chat.websocket.ticket.issue.latency` | Timer | `outcome` | ticket 발급 latency p50/p95/p99 |
| `chat.websocket.ticket.rate_limit.script.failures` | Counter | `scope=user|ip` | Redis Lua script 장애 또는 예상 외 결과 감지 |
| `chat.websocket.ticket.issue.outcomes` | Counter | `intent`, `outcome`, `cohort`, `source` | TODO: 정상 reconnect 실패율과 cohort별 rate limit 영향 계산 |
| `chat.websocket.ticket.rate_limit.sequential_overcount.suspected` | Counter | `cohort` | TODO: user 통과 후 IP 거부로 user counter가 보수적으로 소모된 의심 건수 |
| `chat.websocket.reconnect.attempts` | Counter | `source`, `reason`, `outcome`, `cohort` | TODO: reconnect 시도부터 ticket/handshake 결과까지 release gate 계산 |
| `http.server.requests` | Timer | `uri=/api/ws-tickets`, `status`, `method` | ticket API HTTP latency/error |

권장 패널:

- ticket issue success/failure/rate-limited rate
- ticket issue latency p50/p95/p99
- Lua script failure count by scope
- ticket consume miss/malformed/expired count
- normal reconnect ticket issue success/failure rate
- NAT/proxy/mobile carrier cohort reconnect failure p95
- sequential overcount suspected count

권장 alert 후보:

- `chat.websocket.ticket.rate_limit.script.failures`가 5분 동안 1건 이상 발생
- ticket issue failure 비율이 5분 동안 기준치 이상
- ticket issue latency p95가 기준치 이상
- consume miss가 갑자기 증가
- 정상 reconnect ticket 발급 성공률이 rolling 15분 `99.9%` 미만
- rate limit으로 인한 정상 reconnect 실패율이 `0.1%` 초과

### 3.2 Gateway / Fan-out

| Metric | Type | Tags | 목적 |
| --- | --- | --- | --- |
| active WebSocket connections | Gauge | `role`, `gatewayGroup` | 연결 수와 Gateway 분산 상태 |
| room subscription count | Gauge | `roomHeat`, `gatewayGroup` | hot room 구독 집중도 |
| outbound payload bytes/sec | Counter | `gatewayGroup` | 네트워크 송신량 |
| batch frames/sec | Counter | `gatewayGroup`, `roomHeat` | batch fan-out 처리량 |
| local delivery count/sec | Counter | `gatewayGroup`, `roomHeat` | Gateway local delivery 처리량 |
| send queue depth | Gauge | `gatewayGroup` | slow client 또는 backpressure 감지 |
| WebSocket write latency | Timer | `gatewayGroup`, `roomHeat` | fan-out p95/p99 |
| slow client disconnect count | Counter | `gatewayGroup` | 느린 클라이언트 차단 현황 |
| WebSocket reconnect rate | Counter | `reason` | 장애/배포/reconnect storm 감지 |

권장 alert 후보:

- hot room fan-out p95가 release 기준 초과
- send queue depth가 지속적으로 증가
- reconnect rate 급증
- slow client disconnect가 기준치 이상

### 3.3 Ingest / Worker / Redis Streams

| Metric | Type | Tags | 목적 |
| --- | --- | --- | --- |
| messages accepted/sec | Counter | `roomHeat` | 수락 처리량 |
| messages rejected/sec | Counter | `reason`, `roomHeat` | rate limit, moderation, validation 실패 |
| room messages/sec | Counter | `roomHeat` 또는 top N room | hot room 판정 |
| Redis Streams lag | Gauge | `streamShard`, `consumerGroup` | writer/fanout backlog |
| Redis Streams pending count | Gauge | `streamShard`, `consumerGroup` | 재처리 필요 메시지 |
| writer success/failure count | Counter | `workerRole`, `reason` | DB 저장 성공/실패 |
| writer batch latency | Timer | `workerRole` | 저장 batch latency |
| fanout worker lag | Gauge | `fanoutShard` | fan-out 지연 |
| fanout batch size | DistributionSummary | `fanoutShard` | batch 효율 |

권장 alert 후보:

- Redis Streams lag가 3초 이상 지속
- pending count가 계속 증가
- writer failure rate 증가
- fanout worker lag가 release 기준 초과

### 3.4 PostgreSQL / Search / Admin

| Metric | Type | Tags | 목적 |
| --- | --- | --- | --- |
| PostgreSQL partition write latency | Timer | `partitionType` | hot partition write 병목 |
| PostgreSQL replica lag | Gauge | `replica` | read replica 신선도 |
| admin search latency | Timer | `queryType` | 관리자 검색 SLA |
| admin search scanned partitions | DistributionSummary | `queryType` | 파티션 pruning 여부 |
| search projection lag | Gauge | `projection` | 검색 projection 지연 |
| archive worker run duration | Timer | `result` | partition archive 안정성 |

권장 alert 후보:

- replica lag 3초 이상 지속
- admin search p95가 1초 초과
- archive worker 실패
- 검색 projection lag 증가

### 3.5 Infra / Routing

| Metric 또는 Check | Type | 목적 |
| --- | --- | --- |
| role별 actuator health | Synthetic check | `chat-api`, `chat-websocket`, `chat-admin`, `chat-worker` 상태 |
| nginx upstream role routing check | Synthetic check | `/api/`, `/api/ws/`, `/api/admin/` 오라우팅 감지 |
| container restart count | Counter/Gauge | crash loop 감지 |
| CPU/memory/network/disk IO | Gauge | 리소스 병목 확인 |

권장 alert 후보:

- app rebuild/recreate 후 `/api/`, `/api/ws/`, `/api/admin/`가 잘못된 role로 routing
- nginx health 실패
- 특정 role replica health가 기준 이하

## 4. Phase 7 Release Gate Metric

| Gate | Metric 기준 |
| --- | --- |
| hot room ingest | 10,000 msg/sec 이상을 60초 이상 유지 |
| writer lag | 정상 상황 3초 이하 |
| replica lag | 정상 상황 3초 이하 |
| hot room fan-out | batch fan-out p95 500ms 이하 |
| admin search | 방별/시간대별 조회 p95 1초 이하 |
| ticket rate limit | 단일 key Lua script 기반 원자 처리, fail-closed, script failure metric 관측 |
| ticket latency | `chat.websocket.ticket.issue.latency` p95/p99 관측 |
| ticket reconnect success | 정상 reconnect ticket 발급 성공률 rolling 15분 `99.9%` 이상 |
| ticket rate limit UX | rate limit으로 인한 정상 reconnect 실패율 `0.1%` 이하, NAT/proxy/mobile carrier cohort p95 `0.3%` 이하 |
| ticket user/IP atomicity | 실패율이 전체 `0.5%` 초과 또는 cohort p95 `1.0%` 초과로 rolling 15분 2회 이상 반복될 때 검토 |
| nginx stale upstream | app recreate 후 오라우팅 없음 |

## 5. Log / Trace 필수 필드

- `messageId`
- `roomId`
- `roomSeq`
- `gatewayId`
- `workerId`
- `streamShard`
- `writeShard`
- `fanoutShard`
- rate limit reject reason
- admin action audit fields

민감값은 제외한다.

- session token 원문
- WebSocket ticket 원문
- raw IP
- message body

## 6. 복잡도

- metric 기록 시간 복잡도: 일반적으로 `O(1)`
- metric 기록 공간 복잡도: tag cardinality에 비례
- dashboard query 비용: metric 수와 tag cardinality에 비례

## 7. 주의사항

> - `roomId`를 모든 metric tag에 넣으면 hot room을 찾기 전에 TSDB가 먼저 고비용 상태가 될 수 있다.
> - ticket 관련 metric에는 token/ticket 원문을 절대 넣지 않는다.
> - alert threshold는 문서의 후보값을 그대로 production에 고정하지 않고, staging 부하 테스트 결과로 보정한다.
> - release gate metric은 “관측 가능”과 “기준 충족”을 분리해 판단한다. metric이 있어도 threshold를 넘으면 release fail이다.

## 8. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 애플리케이션 metric 중심 | 코드 경로별 원인 파악이 쉽다 | 인프라 병목을 놓칠 수 있다 | 기본 |
| 인프라 metric 중심 | 운영 환경 전체 상태를 보기 쉽다 | 메시지 처리 의미를 알기 어렵다 | 보조 |
| tracing 중심 | 개별 요청 흐름 분석에 강하다 | 고TPS에서 sampling과 비용 관리 필요 | 장애 분석 보조 |
| log 중심 | 상세 원인 분석 가능 | 실시간 release gate 판단에는 느릴 수 있다 | 감사/디버깅 보조 |
