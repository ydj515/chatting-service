# Observability Metrics

이 문서는 Phase 7 운영 검증과 릴리즈 게이트에서 dashboard와 alert에 연결할 metric 목록을 정리한다.

Reconnect load test 시나리오와 정상 reconnect 실패율 계산 기준은 [phase7_reconnect_load_test_scenarios.md](./phase7_reconnect_load_test_scenarios.md)를 따른다.

## 1. 목표

- hot room ingest, fan-out, 저장, 검색, WebSocket ticket 보안 경로를 한 화면에서 판단한다.
- 장애가 발생했을 때 어느 role이 병목인지 빠르게 좁힌다.
- release gate에서 p95/p99 latency, lag, failure rate를 수치로 확인한다.
- metric tag cardinality를 통제해 운영 비용과 Prometheus/TSDB 부하를 관리한다.

## 1.1 Phase 7 초기 작업 순서

2026-06-20 Phase 7 사전 점검 결과, 초기 작업은 다음 순서로 진행한다.

1. Nginx stale upstream 대응을 먼저 처리한다.
   - `phase6-fanout-takeover-smoke.mjs`가 복구 과정에서 `docker compose up -d --scale ...`을 수행한 뒤, Nginx가 예전 컨테이너 IP로 `/api/users/register`를 라우팅해 404를 반환했다.
   - Nginx 재시작 후 동일 POST 요청은 정상 복구되었다.
   - Phase 7 첫 작업은 app rebuild/recreate/scale 이후 `/api/`, `/api/ws/`, `/api/admin/` role routing synthetic check가 manual Nginx restart 없이 통과하는지 검증하고, 필요하면 resolver 기반 upstream 또는 자동 restart 절차를 확정하는 것이다.
2. Fanout owner kill takeover flake를 다음으로 처리한다.
   - 첫 owner kill takeover smoke에서 `roomSeq order violated: 77 came after 600`이 발생했다.
   - 동일 조건 재실행은 통과했고, 무장애 다중 worker load도 통과했다.
   - 따라서 일반 fanout 경로보다 worker kill 직후 publish-before-ack, pending claim, duplicate replay 구간의 flaky 위험으로 분류한다.

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
| `chat.message.admission.rejected` | Counter | `reason=room_rate_limited|user_rate_limited|slow_mode_active|redis_error|script_error` | Redis admission policy가 메시지 수락을 거부한 이유 |
| `chat.message.moderation.rejected` | Counter | `reason=blocked_word\|muted\|banned`, `scope=global\|room`, `action=reject` | 금칙어, mute, ban으로 메시지 수락 전 거부된 횟수 |
| room messages/sec | Counter | `roomHeat` 또는 top N room | hot room 판정 |
| room traffic snapshot p95 | Gauge 또는 synthetic 계산 | `roomHeat` 또는 top N room | `room-policy` worker의 자동 downgrade 입력값 |
| `chat.redis.stream.append.latency` | Timer | `stream_shard`, `outcome=success|failure` | Redis Streams append p50/p95/p99 |
| `chat.redis.stream.consumer.records` | Counter | `consumer_group`, `source=new|pending_scanned|pending_claimed`, `stream_shard` | consumer group read/claim 처리량과 pending scan 규모 |
| `chat.redis.stream.worker.batch.latency` | Timer | `worker_role=message-writer|fanout`, `outcome=success|partial|failure|lease_lost` | writer/fanout batch 처리 latency |
| `chat.redis.stream.worker.records` | Counter | `worker_role=message-writer|fanout`, `outcome=success|partial|failure|lease_lost` | 처리 결과별 Redis Streams record 수 |
| `chat.redis.stream.dead_letters` | Counter | `consumer_group`, `stream_shard` | max delivery 초과 후 dead-letter stream으로 이동한 record 수 |
| `chat.redis.stream.group.lag` | Gauge | `stream_shard`, `consumer_group` | `XINFO GROUPS`의 group lag를 stream shard 단위로 합산 |
| `chat.redis.stream.group.pending` | Gauge | `stream_shard`, `consumer_group` | `XPENDING` summary pending count를 stream shard 단위로 합산 |
| `chat.room_seq.gap.rooms` | Gauge | 없음 | 최근 canonical audit window에서 `room_seq` gap이 있는 room 수 |
| `chat.room_seq.gap.missing_sequences` | Gauge | 없음 | 최근 canonical audit window에서 비어 있는 `room_seq` 총합 |
| `chat.room_seq.gap.max_width` | Gauge | 없음 | 최근 canonical audit window에서 관측된 가장 큰 단일 gap width |
| `chat.room_seq.gap.scanned_rooms` | Gauge | 없음 | 최근 canonical audit window에서 스캔한 room 수 |
| fanout worker lag | Gauge | `fanoutShard` | fan-out 지연 |
| fanout batch size | DistributionSummary | `fanoutShard` | batch 효율 |
| `chat.fanout.owner.lease.acquire` | Counter | `outcome=success|failure`, `reason` | owner lease 획득 성공/실패 |
| `chat.fanout.owner.lease.renew` | Counter | `outcome=success|failure|lost` | owner lease 갱신 상태 |
| `chat.fanout.owner.lease.lost` | Counter | `reason=expired|token_mismatch|redis_error` | stale owner 또는 lease 상실 감지 |
| `chat.fanout.owner.rooms` | Gauge | `workerRole`, `roomHeat` | worker별 보유 owner room 수 |
| `chat.fanout.owner.takeovers` | Counter | `reason=ttl_expired|pending_claim|worker_restart` | owner 장애 후 takeover |
| `chat.fanout.owner.token_mismatch` | Counter | `stage=before_publish|before_ack` | publish/ack 직전 fencing token 불일치 |

Phase 7 명시 task:

- `chat.fanout.owner.takeovers`의 production 의미를 코드와 문서에서 정합하게 확정한다. 현재 Phase 6 구현은 owner lease 기능, fencing, owner kill smoke를 갖추었지만, metric semantics는 release gate 전에 별도로 검증해야 한다.
- 2026-06-24 Redis Streams worker metric 슬라이스에서 append latency, consumer read/claim records, worker batch latency, worker processed records, dead-letter counters를 추가했다. 이 metric들은 애플리케이션 처리 경로의 event/timer 신호이며, Redis 서버의 전체 backlog를 직접 재는 gauge는 별도 후속 슬라이스로 남긴다.
- 2026-06-24 Redis Streams direct lag gauge 슬라이스에서 `XINFO GROUPS`와 `XPENDING` summary 기반 `chat.redis.stream.group.lag`, `chat.redis.stream.group.pending` gauge를 추가했다. tag는 `stream_shard`, `consumer_group`으로 제한하고 room stream key와 `roomId`는 tag로 쓰지 않는다.
- 2026-06-24 Redis Streams lag alert rule 슬라이스에서 `infra/prometheus/rules/phase7-redis-streams-lag.rules.yml`를 추가했다. rule은 `consumer_group`, `stream_shard` aggregation만 사용하며 warning/critical threshold를 분리한다.
- 2026-06-27 Phase 8.7 roomSeq gap audit 슬라이스에서 `infra/prometheus/rules/phase8-room-seq-gap.rules.yml`를 추가했다. rule은 room별 label 없이 aggregate gauge만 사용하고, sequence allocation 이후 append 실패로 생긴 allowed hole 가능성이 있어 warning으로 시작한다.
- 일반적인 worker death takeover, 즉 worker A 사망 후 TTL 만료와 worker B 신규 acquire가 발생한 경우를 `takeovers` counter가 직접 세는지 확인한다.
- 직접 계측이 어렵다면 `lease.lost`, `pending claim`, smoke runner summary를 조합한 derived signal로 정의하고, 문서의 reason tag를 실제 구현 가능한 값으로 낮춘다.
- 2026-06-20 사전 점검에서 `roomSeq order violated: 77 came after 600` flake가 1회 관측되었으므로, owner kill takeover smoke summary는 raw delivery order와 client-visible render result를 분리해서 기록한다.
- Production release 기준은 분리 검증으로 둔다. steady-state와 무장애 다중 worker 경로는 raw delivery `roomSeq` 역전이 없어야 하고, owner kill/takeover 경로는 raw delivery를 diagnostic signal로 유지하면서 client dedupe/render 후 중복 없는 `roomSeq` 정렬과 gap fill 복구를 release blocking gate로 삼는다.
- Raw takeover 역전이 발생하면 이미 전달된 message의 duplicate replay인지, 처음 보는 과거 message가 뒤늦게 도착한 것인지 분류한다. duplicate replay는 client-visible gate가 통과하고 비율이 제한될 때만 허용 가능하며, 처음 보는 과거 message가 최신 메시지 뒤에 도착해 화면을 흔들면 release fail로 본다.
- 이 task는 fanout 기능 완료 조건이 아니라 Phase 7 observability/release gate 조건이다.

권장 alert 후보:

- `RedisStreamsGroupLagSustained`: `chat_redis_stream_group_lag > 0` for `3m`
- `RedisStreamsGroupLagCritical`: `chat_redis_stream_group_lag > 1000` for `5m`
- `RedisStreamsGroupPendingSustained`: `chat_redis_stream_group_pending > 0` for `5m`
- `RedisStreamsGroupPendingCritical`: `chat_redis_stream_group_pending > 100` for `10m`
- `RoomSeqGapDetected`: `max(chat_room_seq_gap_missing_sequences) > 0` for `2m`
- `RoomSeqGapWidthElevated`: `max(chat_room_seq_gap_max_width) > 100` for `5m`
- writer failure rate 증가
- `chat.message.admission.rejected{reason="redis_error"}`가 5분 동안 1건 이상 발생
- `room_rate_limited`, `user_rate_limited`, `slow_mode_active`가 배포 직후 기준선보다 급증
- fanout worker lag가 release 기준 초과
- `chat.fanout.owner.lease.lost`가 짧은 시간에 급증
- token mismatch가 발생하면서 fanout worker lag도 증가
- 특정 worker의 `chat.fanout.owner.rooms`가 계속 0이거나 한 worker에만 과도하게 집중

### 3.4 PostgreSQL / Search / Admin

| Metric | Type | Tags | 목적 |
| --- | --- | --- | --- |
| PostgreSQL partition write latency | Timer | `partitionType` | hot partition write 병목 |
| PostgreSQL replica lag | Gauge | `replica` | read replica 신선도 |
| admin search latency | Timer | `queryType`, `cacheState=warm|cold`, `scenario=steady_state|post_restart|cold_cache` | 관리자 검색 SLA와 cold gate 분리 |
| admin search scanned partitions | DistributionSummary | `queryType` | 파티션 pruning 여부 |
| search projection lag | Gauge | `projection` | 검색 projection 지연 |
| archive worker run duration | Timer | `result` | partition archive 안정성 |

Phase 7 명시 task:

- 2026-06-24 admin search latency gate 슬라이스에서 `scripts/measure-admin-search-p95.mjs`가 `--gate warm|cold|both`를 지원하도록 확장되었다.
- warm gate는 explicit warmup 이후 `p95 <= 1000ms`와 실패 응답 0건을 요구한다.
- cold gate는 warmup 없는 post-restart 또는 cold-cache synthetic run에서 `p99 <= 6000ms`와 실패 응답 0건을 요구한다.
- `--gate both`는 cold 샘플을 먼저 실행한 뒤 warmup과 warm 샘플을 실행해 두 gate를 같은 JSON report의 `ok`와 `failedGates`로 분리한다.
- 2026-06-24 admin search slow query plan capture 슬라이스에서 `--slow-query-plan on-cold-failure` 옵션을 추가했다. cold p99 실패 endpoint는 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` artifact를 `slowQueryPlans` report field로 남길 수 있다.

권장 alert 후보:

- replica lag 3초 이상 지속
- admin search warm p95가 1초 초과
- admin search cold p99가 6초 초과
- archive worker 실패
- 검색 projection lag 증가

### 3.5 Infra / Routing

| Metric 또는 Check | Type | 목적 |
| --- | --- | --- |
| role별 actuator health | Synthetic check | `chat-api`, `chat-websocket`, `chat-admin`, `chat-worker` 상태 |
| nginx upstream role routing check | Synthetic check | `/api/`, `/api/ws/`, `/api/admin/` 오라우팅 감지 |
| container restart count | Counter/Gauge | crash loop 감지 |
| CPU/memory/network/disk IO | Gauge | 리소스 병목 확인 |

Phase 7 명시 task:

- 2026-06-20 사전 점검에서 app container recreate 후 Nginx stale upstream이 실제로 재현되었다. `phase6-fanout-takeover-smoke.mjs` restore scale 이후 `/api/users/register`가 404를 반환했고, Nginx restart 후 정상 복구되었다.
- Phase 7 첫 작업은 app rebuild/recreate/scale 직후 role routing synthetic check를 실행해 stale upstream을 재현하고, resolver 기반 upstream, 동적 service discovery, 또는 자동 Nginx restart 중 하나를 운영 절차로 확정하는 것이다.
- 이 check는 `/api/actuator/health`, `/api/ws/chat`의 expected WebSocket handshake behavior, `/api/admin/health`, `POST /api/users/register`, `POST /api/ws-tickets`를 role별로 나눠 검증한다.

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
| admin search warm p95 | steady-state warmup 이후 방별/시간대별 조회와 `FTS` 검색 p95 1초 이하 |
| admin search cold p99 | app 재시작 또는 cold-cache synthetic run에서 방별/시간대별 조회와 `FTS` 검색 p99 6초 이하 |
| ticket rate limit | 단일 key Lua script 기반 원자 처리, fail-closed, script failure metric 관측 |
| message admission | room 단위 hash tag Lua script 기반 원자 처리, fail-closed, reject reason metric 관측 |
| fanout owner takeover observability | `chat.fanout.owner.takeovers`의 실제 계측 semantics가 문서와 일치하거나, `lease.lost`/pending claim 기반 derived signal로 대체 정의됨 |
| ticket latency | `chat.websocket.ticket.issue.latency` p95/p99 관측 |
| ticket reconnect success | 정상 reconnect ticket 발급 성공률 rolling 15분 `99.9%` 이상 |
| ticket rate limit UX | rate limit으로 인한 정상 reconnect 실패율 `0.1%` 이하, NAT/proxy/mobile carrier cohort p95 `0.3%` 이하 |
| ticket user/IP atomicity | 실패율이 전체 `0.5%` 초과 또는 cohort p95 `1.0%` 초과로 rolling 15분 2회 이상 반복될 때 검토 |
| nginx stale upstream | app recreate 후 오라우팅 없음 |
| Redis Streams worker observability | append latency, consumer read/claim records, worker batch latency, dead-letter 이동이 bounded tag로 관측됨 |
| admin search latency gate | warm p95와 cold p99가 별도 gate로 계산되고 `failedGates`에 분리 기록됨 |
| admin search slow plan capture | cold p99 실패 시 선택적으로 PostgreSQL JSON plan artifact가 남음 |
| Redis Streams direct lag gauge | `chat.redis.stream.group.lag`와 `chat.redis.stream.group.pending`이 bounded tag로 관측됨 |
| Redis Streams lag alert rule | lag/pending warning과 critical Prometheus rule이 bounded label로 정의됨 |
| Phase 8.4 hot room shard 분산 | `count(count by (stream_shard) (chat_redis_stream_group_lag{consumer_group="fanout",stream_shard!="unknown"}))` 16 이상 |
| Phase 8.4 fanout p95 | `histogram_quantile(0.95, sum(rate(chat_redis_stream_worker_batch_latency_seconds_bucket{worker_role="fanout",outcome="success"}[1m])) by (le))` 0.5초 이하 |
| Phase 8.4 stream lag | `max(chat_redis_stream_group_lag{stream_shard!="unknown"})` 1000 entries 이하 |

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
