# Production Hardening Tasks

이 문서는 Phase 2.5 이후 운영 공개 전 또는 운영 안정화 단계에서 분리했거나 완료한 hardening 항목을 정리한다.

---

## 1. WebSocket Ticket Rate Limit Lua Script 전환

### 상태

- 분류: Security / Reliability Hardening
- 적용 시점: Phase 2.5 완료 후 Production Hardening Gate
- 현재 구현 상태: 반영 완료
- 관련 문서: [ws_ticket_analysis.md](./ws_ticket_analysis.md), [redis_cluster_key_naming.md](./redis_cluster_key_naming.md), [observability_metrics.md](./observability_metrics.md)

### 문제

WebSocket one-time ticket 발급 API는 Redis rate limit counter를 사용한다. 단순 구현은 다음 두 명령을 순서대로 호출한다.

```text
INCR rate-limit-key
EXPIRE rate-limit-key window
```

이 구조에서 `INCR` 성공 후 `EXPIRE` 전에 애플리케이션 crash, Redis timeout, 네트워크 단절이 발생하면 TTL 없는 rate limit key가 남을 수 있다. 이 key가 계속 증가하거나 남아 있으면 특정 user/IP가 계속 rate limited 상태처럼 보일 수 있고, 사용자는 WebSocket ticket 발급 실패와 재연결 실패를 경험한다.

### 현재 완화책

초기 완화책은 `count == 1`일 때 TTL을 설정하고, 이후 요청에서 `TTL == -1`을 발견하면 같은 window로 TTL을 복구하는 방식이었다. 현재 구현은 이 경로를 Lua script로 승격해 Redis 내부에서 원자 처리한다.

### 목표 구현

Redis Lua script로 `INCR + PEXPIRE + TTL repair`를 Redis 내부에서 원자 처리한다.

예시:

```lua
local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])

if current == 1 or ttl == -1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end

if current <= tonumber(ARGV[2]) then
  return 1
end

return 0
```

`ARGV[1]`은 window milliseconds, `ARGV[2]`는 limit이다.

### Redis Cluster Hash Slot 판단

현재 Lua script는 Redis Cluster hash slot 문제를 피하도록 **단일 key script**로 유지한다.

- user rate limit script는 `chat:ws-ticket:rate:user:<userId>` 계열 key 1개만 `KEYS[1]`로 받는다.
- IP rate limit script는 `chat:ws-ticket:rate:ip:<hashedIp>` 계열 key 1개만 `KEYS[1]`로 받는다.
- user key와 IP key를 같은 Lua script에 함께 넘기지 않는다.
- Lua script 내부에서 `KEYS[1]` 외의 Redis key를 조합하거나 접근하지 않는다.

Redis Cluster에서 Lua script가 여러 key를 받으면 모든 key가 같은 hash slot에 있어야 한다. 현재 구현은 script 호출 1회당 key가 1개뿐이므로 cross-slot 문제가 발생하지 않는다. 따라서 현 단계에서는 rate limit key에 hash tag를 강제로 붙일 필요가 없다.

향후 user rate limit과 IP rate limit을 하나의 script에서 완전 원자 처리하려면 두 key가 같은 hash slot에 있어야 한다. 다만 모든 key를 `{ws-ticket}` 같은 공통 hash tag로 묶으면 한 slot에 트래픽이 몰릴 수 있으므로 기본 방향으로 두지 않는다. 그 경우에는 다음 중 하나를 별도 설계로 선택한다.

- user/IP를 계속 단일 key script로 분리하고, 두 제한의 완전 원자성은 포기한다.
- 같은 hash slot이 필요한 범위만 hash tag로 묶되 slot skew와 hot key 위험을 별도 검증한다.
- Lua script 대신 RedisGears, 별도 rate limit service, 또는 edge/WAF rate limit과 조합한다.

현재 구현의 운영 기준은 **각 제한을 단일 key script로 fail-closed 처리하는 것으로 충분한지 Phase 7에서 판단**하는 것이다. user limit 통과 후 IP limit에서 거부되면 user counter가 보수적으로 소모될 수 있지만, 이는 제한 우회가 아니라 정상 reconnect UX에 영향을 줄 수 있는 문제다. 따라서 완전 원자성 도입 여부는 다음 지표를 보고 결정한다.

- IP limit 거부 중 user counter 소모로 정상 reconnect가 실패하는 비율
- NAT, corporate proxy, mobile carrier 환경에서 동일 IP 사용자들이 받는 rate limit 영향
- ticket issue latency p95/p99
- Redis Lua script failure 발생률
- ticket issue failure 중 `rate_limited_user`, `rate_limited_ip`, `failure` 비율
- abuse traffic에서 현재 순차 fail-closed 방식으로 충분히 방어되는지 여부

운영 판단 기준은 다음으로 고정한다.

- 전체 정상 reconnect ticket 발급 성공률이 rolling 15분 기준 `99.9%` 이상이면 현재 단일 key Lua script + fail-closed 방식을 유지한다.
- rate limit으로 인한 정상 reconnect 실패율이 `0.1%` 이하이면 현재 방식을 유지한다.
- NAT/proxy/mobile carrier cohort의 정상 reconnect rate limit 실패율 p95가 `0.3%` 이하이면 현재 방식을 유지한다.
- 위 실패율이 전체 `0.1%` 초과 `0.5%` 이하이거나 cohort p95 `0.3%` 초과 `1.0%` 이하이면 완전 원자성보다 limit/window/burst 정책을 먼저 튜닝한다.
- 정책 튜닝 후에도 전체 실패율 `0.5%` 초과 또는 cohort p95 `1.0%` 초과가 rolling 15분 기준 2회 이상 반복되면 multi-key 원자 처리 또는 별도 rate limit service를 검토한다.
- IP limit 거부 후 user counter 보수적 소모로 추정되는 실패가 정상 reconnect 시도 중 `0.2%`를 넘는 구간이 rolling 15분 기준 2회 이상 반복되면 완전 원자성 도입을 검토한다.

상세 Redis key naming, hash tag 정책, 판단 기준표는 [redis_cluster_key_naming.md](./redis_cluster_key_naming.md)를 따른다. Phase 7 dashboard와 alert 연결 기준은 [observability_metrics.md](./observability_metrics.md)를 따른다.

### 완료 기준

- user 기준 ticket issue rate limit이 Lua script 경로를 사용한다.
- IP 기준 ticket issue rate limit이 Lua script 경로를 사용한다.
- Lua script 실행 실패 시 ticket 발급은 fail-closed로 실패한다.
- `INCR` 이후 TTL이 없는 key가 남는 장애 모드가 단위 테스트로 방어된다.
- Redis Cluster 전환 시 단일 key script라 hash tag 없이 cross-slot 문제가 없다는 판단이 문서화되어 있다.
- ticket issue success/failure/rate-limited count가 metric으로 관측된다.
- ticket issue latency가 `chat.websocket.ticket.issue.latency` timer로 관측된다.
- Redis Lua script 실패가 `chat.websocket.ticket.rate_limit.script.failures` counter로 관측된다.
- Lua script failure metric은 `scope=user|ip` tag를 가진다.
- Phase 7에서는 위 metric을 dashboard와 alert rule에 연결한다.

### 복잡도

- 시간 복잡도: `O(1)`
- 공간 복잡도: `O(1)`

### 주의사항

> - Redis Cluster에서 Lua script는 같은 hash slot의 key만 한 번에 다룰 수 있다. user key와 IP key를 하나의 script에서 동시에 처리하지 않는 편이 단순하다.
> - 현재 rate limit script는 호출 1회당 key 1개만 사용하므로 hash tag가 필요 없다. 여러 key를 한 script로 합치면 Redis Cluster hash slot 설계를 다시 해야 한다.
> - Lua script 장애 시 fail-open으로 ticket을 발급하면 abuse 방어가 깨질 수 있다. 운영 기본값은 fail-closed가 맞다.
> - 이전 TTL repair 방식은 임시 완화책이며, 현재 최종 구현은 Lua script 원자 처리다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 현재 TTL repair 유지 | 구현이 단순하고 장애 모드를 일부 완화한다 | `INCR + EXPIRE` 사이 중간 상태 자체는 남는다 | 더 이상 기본안으로 두지 않음 |
| Lua script 전환 | 원자성, round-trip 감소, 장애 모드 축소 | script 관리와 Redis Cluster 제약 검토 필요 | 적용 완료 |
| Redis transaction 사용 | 명령 묶음 의도가 명확하다 | Redis transaction은 중간 로직과 조건 처리에 Lua보다 불편하다 | 우선순위 낮음 |

---

## 2. Docker Compose Nginx Upstream DNS Stale 대응

### 상태

- 분류: Infra / Deployment Hardening
- 적용 시점: 별도 Infra Hardening task
- 현재 구현 상태: `mise run start`(내부 `start:backend`), `start-cluster.sh`에 nginx restart 반영 완료
- 관련 문서: [infrastructure.md](./infrastructure.md)

### 문제

Docker Compose에서 app 컨테이너를 재생성하면 컨테이너 IP가 바뀔 수 있다. Nginx는 일반적인 `upstream` 설정에서 hostname을 시작 시점에 resolve하고, 재기동 전까지 이전 IP를 계속 사용할 수 있다.

이전 IP가 다른 role의 컨테이너에 재사용되면 다음과 같은 일이 발생할 수 있다.

1. `chat-api-app-*` 컨테이너가 재생성된다.
2. 기존 API 컨테이너 IP가 `chat-websocket-app-*` 같은 다른 컨테이너에 재사용된다.
3. nginx는 예전 API upstream IP를 계속 사용한다.
4. `/api/users/register` 요청이 WebSocket 앱으로 전달된다.
5. WebSocket 앱에는 REST user controller가 없으므로 `404`가 발생한다.

### 영향

- 로컬 Docker Compose 또는 Compose 기반 스테이징에서 재현 가능하다.
- Kubernetes 환경에서는 Service/Ingress가 stable virtual endpoint를 제공하므로 같은 형태의 문제가 줄어든다.
- VM + Docker Compose 운영을 선택한다면 배포 절차에서 반드시 다뤄야 한다.

### 현재 runbook

app 컨테이너 재생성 후 nginx를 재시작해 upstream DNS를 다시 resolve한다.

```bash
mise run start
mise run verify:chat
```

수동으로 app 컨테이너만 재생성한 경우에는 다음 명령을 실행한다.

```bash
mise run restart:nginx
mise run verify:chat
```

### 목표 구현

현재는 첫 번째 방식을 적용했다. 필요해지면 두 번째 또는 세 번째 방식으로 확장한다.

1. Compose 배포 스크립트에 app 재생성 후 `docker compose restart nginx`를 명시적으로 포함한다.
2. nginx에 Docker DNS resolver(`127.0.0.11`)와 동적 `proxy_pass` 전략을 적용한다.
3. 운영 배포는 Kubernetes Service/Ingress 기반으로 전환하고 Compose는 local/dev 전용으로 제한한다.

### 완료 기준

- app 컨테이너를 재생성한 뒤에도 `/api/`, `/api/ws/`, `/api/admin/` 요청이 올바른 role로 라우팅된다.
- `mise run verify:chat`가 app rebuild 직후 안정적으로 통과한다.
- nginx access log의 `upstream` 주소가 현재 container IP와 일치한다.
- 배포 runbook 또는 mise task에 nginx stale DNS 대응 절차와 health wait가 포함되어 있다.

### 복잡도

- 요청 처리 시간 복잡도: `O(1)`
- 추가 공간 복잡도: `O(1)`

### 주의사항

> - `docker compose up -d --build`만으로는 nginx가 기존 upstream IP를 갱신하지 않을 수 있다.
> - nginx `resolver` 기반 동적 proxy는 설정이 복잡해질 수 있으므로 local/dev와 운영 배포 방식을 분리해서 판단해야 한다.
> - 이 문제는 애플리케이션 controller mapping 문제가 아니라 배포/라우팅 계층의 stale endpoint 문제다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| app 재생성 후 nginx restart | 단순하고 즉시 효과가 있다 | nginx 연결이 재시작된다 | Compose local/staging에 적합 |
| nginx dynamic DNS 설정 | nginx 재시작 빈도를 줄일 수 있다 | 설정 복잡도와 검증 비용이 늘어난다 | 필요 시 적용 |
| Kubernetes Service/Ingress | 운영 표준에 가깝고 endpoint 추상화가 강하다 | 클러스터 운영 비용이 있다 | 최종 운영 환경 권장 |

---

## 3. Admin Export Atomic Manifest 전환

### 상태

- 분류: Reliability / Data Correctness Hardening
- 적용 시점: Production Hardening task
- 현재 구현 상태: 미적용. 현재는 chunk pagination + cursor checkpoint resume까지 완료
- 관련 문서: [export_atomic_manifest_hardening.md](./export_atomic_manifest_hardening.md)

### 문제

현재 admin export worker는 chunk를 CSV 파일에 append한 뒤 DB checkpoint를 갱신한다. 이 구조는 worker crash 후 재개할 수 있지만 다음 짧은 장애 창은 남는다.

```text
chunk append 성공
  -> DB checkpoint 저장 전 worker crash
  -> job requeue
  -> 이전 checkpoint부터 재시도
  -> 마지막 chunk 중복 append 가능
```

이는 데이터 유실 문제가 아니라 duplicate 가능성 문제다. 현재 Phase 5/7 완료 조건에는 포함하지 않고, export 결과가 감사/법무/정산/외부 제출 자료가 되는 시점에 hardening gate로 승격한다.

### 목표 구현

Atomic manifest 방식으로 part file 작성과 final output 공개를 분리한다.

1. chunk를 `part-000001.csv` 같은 독립 part file로 작성한다.
2. part write와 checksum 검증이 끝난 뒤 manifest에 part metadata를 기록한다.
3. resume 시 manifest에 기록된 complete part는 재사용하고, manifest에 없는 임시 파일은 폐기한다.
4. 모든 part가 완료되면 manifest 기준으로 final CSV를 assemble한다.
5. final object publish를 idempotent하게 처리한 뒤 job을 `COMPLETED`로 전환한다.

### 완료 기준

- chunk write와 checkpoint 저장 사이 crash가 발생해도 complete part 중복이 생기지 않는다.
- manifest에 없는 partial file은 resume 대상에서 제외된다.
- final CSV는 manifest에 기록된 complete part만 포함한다.
- final output publish는 idempotent하다.
- manifest checksum mismatch가 발생하면 job은 fail-closed로 실패한다.
- retry/requeue 후 같은 job을 여러 번 실행해도 최종 row count가 변하지 않는다.

### 복잡도

- 시간 복잡도: export 대상 `M`건 기준 `O(M)`
- 공간 복잡도: worker memory 기준 `O(chunkSize)`, manifest metadata 기준 `O(partCount)`

### 주의사항

> - atomic manifest 전환은 현재 완료 조건이 아니라 Production Hardening 후보이다.
> - Object Storage는 일반 파일 시스템 rename과 semantics가 다르므로 conditional put, versioned object, staging path 전략을 별도 검증해야 한다.
> - part cleanup 정책이 없으면 failed/retried job의 임시 파일이 누적될 수 있다.
> - 정확성은 좋아지지만 manifest, checksum, lifecycle 관리로 운영 복잡도가 늘어난다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 현재 chunk checkpoint 유지 | 단순하고 이미 구현되어 있다 | 파일 append 후 checkpoint 전 crash 시 중복 가능 | 현재 기본값 |
| DB row-level export ledger | 중복 방지 강함 | export row 수만큼 DB write가 늘어 부담이 크다 | 비추천 |
| manifest + part file 방식 | resume 안정성과 duplicate 방지가 좋다 | Object Storage/manifest 운영 복잡도 증가 | hardening 후보 |
| 실패 시 final file 전체 재생성 | 구현이 비교적 단순하다 | 대량 export retry 비용이 크다 | 소규모 export에만 적합 |

---

## 4. Fanout Worker Redis TTL Owner Lease

### 상태

- 분류: Realtime Ordering / Worker Scale-out Hardening
- 적용 시점: Phase 6 production gate
- 현재 구현 상태: 적용. `FanoutOwnerLeaseService`와 `HotRoomFanoutWorker` owner gate, Docker profile 설정, metric 이름, owner kill takeover smoke를 추가
- 관련 문서: [Phase 6 Fanout Owner Lease Plan](./superpowers/plans/2026-06-18-phase-6-fanout-owner-lease.md)

### 문제

`MessageWriterWorker`는 Redis Streams consumer group으로 record를 분산해도 `messageId` 멱등 저장으로 중복 처리를 흡수할 수 있다. 하지만 `HotRoomFanoutWorker`는 사용자 화면에 직접 batch를 publish하므로 같은 방의 record를 여러 worker가 동시에 처리하면 순서가 뒤집힐 수 있다.

예를 들어 worker A가 `roomSeq=101..200` batch를 publish하기 전에 worker B가 `roomSeq=201..300` batch를 먼저 publish하면, 클라이언트는 같은 방의 live feed를 서버 수락 순서와 다르게 보게 된다. 방 `id=3`에서 이미 sequence block 선할당으로 화면 정렬 역전 문제가 확인되었으므로, fanout worker 다중화에서도 같은 종류의 순서 위험을 막아야 한다.

### 구현 내용

Redis TTL lease 기반 방별 fanout owner를 둔다.

1. owner key는 `chat:fanout:owner:room:<roomId>:shard:<streamShard>`를 사용한다.
2. owner value는 `<workerId>:<leaseToken>`으로 저장한다.
3. owner 획득은 `SET NX PX <ttlMillis>`로 처리한다.
4. renew/release는 현재 value가 자신의 token과 일치할 때만 동작하는 Lua script로 처리한다.
5. owner worker만 해당 room/stream shard의 `XREADGROUP`, batch publish, `XACK`를 수행한다.
6. publish 직전과 ack 직전에 owner token을 다시 확인한다.
7. owner worker가 죽으면 TTL 만료 후 다른 worker가 takeover하고 pending entry를 claim한다.

운영 기본값은 TTL `10000ms`, renew interval `3000ms`로 확정한다. owner worker는 보유 lease별 마지막 renew 시각을 기준으로 3초마다 TTL을 연장하고, 10초 동안 renew되지 않으면 다른 worker가 owner를 획득할 수 있다.

검증:

- `scripts/phase6-fanout-takeover-smoke.mjs`는 Redis owner lease value의 worker hostname을 Docker container에 매핑해 실제 owner를 kill한다.
- 2026-06-19 로컬 Compose smoke에서 room `14`, `sent=600`, `receivedPerViewer=[600,600,600]`, `assertedRoomSeqOrder=true`로 통과했다.
- 이 smoke 중 raw WebSocket load client가 continuation frame을 처리하지 않아 큰 backlog batch 수신량을 낮게 오판하는 문제가 확인되었고, `RawWebSocketFrameDecoder`로 수정했다.

### 완료 기준

- fanout worker replica를 2개 이상 띄워도 같은 room/stream shard의 active owner는 1개다.
- 같은 방의 `CHAT_MESSAGE_BATCH`가 `roomSeq` 순서대로 publish된다.
- worker kill 후 TTL 만료와 takeover로 fanout이 재개된다.
- stale owner는 token mismatch를 감지하고 publish/ack를 수행하지 않는다.
- owner acquire/renew/lost/takeover metric과 alert 후보가 준비되어 있다.

### 복잡도

- owner acquire/renew/validate/release 시간 복잡도: `O(1)`
- Redis owner lease 공간 복잡도: `O(activeRoomShards)`
- worker 메모리 공간 복잡도: `O(ownedRoomShards)`

### 주의사항

> - Redis TTL lease는 exactly-once delivery를 보장하지 않는다. publish 후 ack 전 장애가 나면 takeover worker가 같은 메시지를 다시 publish할 수 있으므로 클라이언트 `messageId` deduplication이 필요하다.
> - TTL이 너무 짧으면 정상 worker가 lease를 자주 잃고, 너무 길면 장애 takeover가 늦어진다.
> - owner lease는 같은 방의 ordered fanout을 직렬화하는 장치다. 같은 방 내부를 여러 lane으로 쪼개려면 별도 merge 계약이 필요하다.
> - admission policy처럼 DTO를 Redis cache에 저장하는 값은 type hint를 보존해야 한다. Phase 6 smoke에서 `RoomAdmissionPolicy`가 `LinkedHashMap`으로 복원되어 WebSocket send path가 실패했으므로, cache serializer 변경 시 실제 runtime round-trip을 검증한다.
> - owner kill smoke처럼 TTL 동안 backlog가 쌓이는 테스트에서는 `CHAT_MESSAGE_BATCH`가 큰 WebSocket frame으로 전송될 수 있다. 검증 클라이언트가 continuation frame을 처리하지 않으면 서비스 장애가 아닌 계측 오류로 수신량이 낮게 보일 수 있다.
> - 로컬 smoke는 Buildx 정체로 clean Docker image rebuild 대신 Java 17 `bootJar`를 running container에 교체해 수행했다. production release gate에서는 clean image build 후 동일 smoke를 다시 수행한다.
> - `chat.fanout.owner.takeovers` metric의 production 의미는 Phase 7 observability task에서 코드와 문서의 정합성을 다시 검증한다. 일반적인 worker death 후 TTL takeover를 직접 counter로 셀지, `lease.lost`/pending claim/smoke summary 기반 derived signal로 정의할지 release gate 전에 확정한다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Redis TTL owner lease | 현재 Redis 기반 구조에서 구현 비용이 낮고 takeover가 단순함 | TTL/renew 튜닝과 stale owner fencing이 필요 | Phase 6 기본안 |
| Redis Streams consumer group만 사용 | 구현이 가장 단순함 | 같은 방 batch publish 순서가 깨질 수 있음 | fanout 다중 replica에는 부적합 |
| Kafka partition 기반 fanout | partition key로 방별 순서 보장이 명확함 | Kafka 미사용 전제와 다르고 운영 비용 증가 | 장기 대안 |
| 전용 coordinator 서비스 | ownership audit와 fencing을 강하게 통제 가능 | 신규 서비스 운영 부담 증가 | Redis lease 한계 확인 후 검토 |

---

## 5. Message Admission Redis Lua Policy

### 상태

- 분류: Hot Room Ingest / Abuse Control / Overload Protection
- 적용 시점: Phase 6 production gate
- 현재 구현 상태: 적용. `RedisMessageAdmissionPolicyService`, admin room policy override, `roomAdmissionPolicies` cache, 429 API 매핑, `moderatorPriority` 정책 연결, admin override 보호용 `autoPolicyEnabled` 추가
- 관련 문서: [Phase 6 Fanout Owner Lease Plan](./superpowers/plans/2026-06-18-phase-6-fanout-owner-lease.md), [Redis Cluster Key Naming Policy](./redis_cluster_key_naming.md)

### 문제

트위치 같은 스트리밍 채팅에서는 hot room이 순간적으로 10,000 msg/sec 이상까지 튈 수 있다. 모든 메시지를 canonical store에 저장하는 정책은 유지하더라도, 수락량 자체를 제어하지 않으면 Redis sequence, Streams append, worker lag, WebSocket fanout이 함께 밀린다.

또한 room rate limit과 user rate limit을 별도 Redis 명령으로 처리하면 한 메시지가 room counter만 소모한 뒤 user limit에서 거부되는 부분 차감 문제가 생길 수 있다. 이 문제는 abuse 방어에는 보수적일 수 있지만 정상 사용자 UX를 악화시키고, 운영자가 방 정책을 튜닝할 때 원인을 헷갈리게 만든다.

### 구현 내용

Redis Cluster를 고려해 같은 방의 admission key를 `{<roomId>}` hash tag로 같은 slot에 묶고, Lua script 한 번으로 room/user rate limit과 slow mode를 판단한다.

```text
chat:admission:room:{<roomId>}:rate:room:<epochSecond>
chat:admission:room:{<roomId>}:rate:user:<userId>:<epochSecond>
chat:admission:room:{<roomId>}:slow:user:<userId>
```

허용될 때만 room/user counter를 증가시키고 slow mode TTL key를 설정한다. 거부되면 `MessageAdmissionRejectedException`을 던지고 REST API는 HTTP 429, WebSocket은 `MESSAGE_ADMISSION_REJECTED` 에러로 변환한다. 정책 값은 `room_storage_configs`에서 읽되 메시지 hot path의 DB 부하를 줄이기 위해 `roomAdmissionPolicies` cache를 사용하고, admin override 시 해당 room cache를 evict한다. `MemberRole.OWNER/ADMIN`은 `moderatorPriority=true`인 방에서만 admission 제한을 우회한다.

admin policy override는 기본적으로 `autoPolicyEnabled=false`를 저장한다. 이는 자동 `room-policy` worker가 운영자가 수동으로 낮춘 live feed/rate/slow-mode 정책을 다음 poll에서 되돌리는 문제를 막기 위한 보호 장치다. 자동 정책을 다시 허용하려면 admin policy API로 `autoPolicyEnabled=true`를 명시한다.

### 완료 기준

- room rate limit 초과는 sequence 발급과 stream append 전에 거부된다.
- user rate limit 초과는 sequence 발급과 stream append 전에 거부된다.
- slow mode TTL 안의 재전송은 sequence 발급과 stream append 전에 거부된다.
- 동일 `clientMessageId` 재시도는 기존 메시지를 반환하고 admission counter를 다시 소모하지 않는다.
- Redis script 실패 시 fail-closed로 거부하고 `chat.message.admission.rejected{reason="redis_error"}` metric을 남긴다.
- admin room policy override는 audit log를 남기고 admission policy cache를 evict한다.
- WebSocket 경로에서도 제한 초과는 `MESSAGE_ACCEPTED` 없이 `MESSAGE_ADMISSION_REJECTED` error event로 내려간다.
- `OWNER/ADMIN` role은 `moderatorPriority=true`일 때 admission 제한을 우회하고, false이면 제한 대상이 된다.

### 복잡도

- admission 판단 시간 복잡도: `O(1)`
- Redis admission key 공간 복잡도: `O(activeRoomSecondBuckets + activeUserSecondBuckets + activeSlowModeUsers)`
- room policy cache 공간 복잡도: `O(activeRoomsWithPolicyCache)`

### 주의사항

> - room 단위 hash tag는 원자성을 주지만 very hot room 하나가 특정 Redis slot을 압박할 수 있다.
> - rate limit과 slow mode는 저장률을 낮추는 정책이 아니라 수락 전 backpressure 정책이다. 수락된 메시지는 계속 canonical store에 저장되어야 한다.
> - Redis script 실패는 fail-open이 아니라 fail-closed다. 장애 중 정상 메시지가 거부될 수 있으므로 metric과 alert가 필요하다.
> - admin room policy PATCH의 null 필드는 기존 값을 유지한다. 기존 rate limit이나 slow mode를 명시적으로 해제하는 API semantics는 별도 필드 또는 별도 endpoint로 추가해야 한다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| room 단위 hash tag Lua | room/user/slow-mode 판정을 원자 처리 | very hot room slot hotspot 가능 | Phase 6 기본안 |
| 제한별 단일 key script | Redis slot 분산이 자연스러움 | 한 메시지에서 counter 부분 소모 가능 | ticket rate limit에는 유지, message admission에는 미채택 |
| 별도 admission service | Redis Cluster 제약과 정책 캐시를 서비스 내부로 숨김 | 신규 서비스 운영 복잡도 증가 | Phase 7 이후 병목 확인 시 검토 |
