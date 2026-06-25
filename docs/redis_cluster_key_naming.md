# Redis Cluster Key Naming Policy

이 문서는 Redis Cluster 전환을 전제로 sequence, presence, ticket, rate limit, fanout owner lease key naming과 hash slot 정책을 정리한다.

Phase 8.2 Compose 기준 전체 Docker backend는 `cluster` profile의 3 master + 3 replica Redis Cluster를 기본 Redis topology로 사용한다. 호스트 Gradle 개발 모드는 Docker Desktop redirect endpoint 문제를 피하기 위해 `dev` profile의 standalone Redis를 유지하지만, 앱 컨테이너는 `redis-cluster` Spring profile과 Lettuce cluster mode로 동작한다.

## 1. 원칙

- key prefix는 기능 도메인을 먼저 드러낸다.
- 문서 placeholder는 `<roomId>`, `<userId>`처럼 angle bracket을 사용한다.
- Redis hash tag는 의도적으로 같은 slot에 묶어야 할 때만 `{room:<roomId>}`처럼 사용한다.
- 단일 key 명령(`GET`, `SET`, `GETDEL`, `INCRBY`, 단일 key Lua script`)에는 hash tag를 필수로 두지 않는다.
- multi-key command, multi-key Lua script, transaction에서 여러 key를 함께 다룰 때만 같은 hash slot을 요구한다.
- 민감값은 key에 원문으로 넣지 않는다. ticket 원문, session token, IP 원문은 hash 후 사용한다.
- 모든 key에는 가능한 TTL 또는 명확한 lifecycle owner를 둔다.
- metric tag와 마찬가지로 key cardinality도 의도적으로 관리한다. user/session/ticket 단위 key는 TTL을 짧게 둔다.

> Redis Cluster에서 `{...}`는 단순 placeholder가 아니라 hash tag다. 예를 들어 `chat:{room:42}:seq`와 `chat:{room:42}:stream:0`은 같은 hash slot으로 간다. 반대로 `chat:seq:room:42`는 일반 key naming이며 slot은 key 전체 hash로 결정된다.

## 2. 현재 권장 Key

| 도메인 | Key pattern | 자료구조 | Hash tag 필요 여부 | Lifecycle |
| --- | --- | --- | --- | --- |
| room sequence counter | `chat:seq:room:<roomId>` | String counter | 불필요 | 영구 또는 room archive 시 삭제 |
| user presence | `chat:presence:user:<userId>` | Set/Hash | 불필요 | TTL refresh |
| room gateway index | `chat:presence:room:<roomId>:gateways` | Set | 불필요 | TTL refresh |
| gateway room index | `chat:gateway:<gatewayId>:rooms` | Set | 불필요 | Gateway 종료/TTL |
| WebSocket ticket | `chat:ws-ticket:<sha256(ticket)>` | String JSON | 불필요 | ticket TTL |
| WebSocket ticket user rate limit | `chat:ws-ticket:rate:user:<userId>` | String counter | 불필요 | rate limit window TTL |
| WebSocket ticket IP rate limit | `chat:ws-ticket:rate:ip:<sha256(ip)>` | String counter | 불필요 | rate limit window TTL |
| message room rate limit | `chat:admission:room:{<roomId>}:rate:room:<epochSecond>` | String counter | 필요 | 짧은 TTL |
| message user rate limit | `chat:admission:room:{<roomId>}:rate:user:<userId>:<epochSecond>` | String counter | 필요 | 짧은 TTL |
| message slow mode | `chat:admission:room:{<roomId>}:slow:user:<userId>` | String | 필요 | slow mode TTL |
| room traffic counter | `chat:room-traffic:{<roomId>}:sec:<epochSecond>` | String counter | 필요 | traffic counter TTL |
| active room traffic index | `chat:room-traffic:active-rooms` | Sorted Set | 불필요 | score 기반 cleanup |
| room stream shard | `chat:stream:room:<roomId>:shard:<shardNo>` | Redis Stream | 불필요 | retention 정책 |
| fan-out pub/sub channel | `chat:pubsub:room:<roomId>:shard:<shardNo>` | Channel | 불필요 | ephemeral |
| fanout owner lease | `chat:fanout:owner:room:<roomId>:shard:<streamShard>` | String | 불필요 | TTL lease |

현재 권장안은 대부분 단일 key 연산이므로 hash tag를 기본으로 쓰지 않는다. 이렇게 해야 Redis Cluster가 key를 자연스럽게 여러 slot에 분산한다.

### 2.1 Room Sequence Ordering 정정

2026-06-18 방 `id=3` 검증에서 sequence block 선할당이 트위치식 실시간 채팅 순서를 깨는 문제가 확인되었다.

```text
실제 생성 시간:
18:55:22.695 room_seq=1001 sender=2
18:55:25.823 room_seq=1016 sender=2
18:55:27.158 room_seq=46   sender=3
18:56:03.858 room_seq=53   sender=3

roomSeq 정렬 화면:
46..53 메시지가 1001..1016 메시지보다 먼저 표시됨
```

원인은 두 WebSocket Gateway가 같은 방에 대해 서로 다른 sequence block을 미리 확보한 뒤 로컬 메모리에서 소진했기 때문이다. 이 구조는 sequence gap만 만드는 것이 아니라, 서로 다른 Gateway에 붙은 사용자의 메시지를 실제 수락 순서와 다르게 정렬할 수 있다. 따라서 room sequence는 block allocation이 아니라 단일 Redis counter에 대한 메시지별 `INCR 1`을 사용한다. gapless sequence는 여전히 요구하지 않지만, 발급된 `roomSeq`의 상대 순서는 방 단위 서버 수락 순서를 따라야 한다.

### 2.2 Fanout Owner Lease

Phase 6 production fanout worker scale-out은 Redis TTL lease 기반 방별 owner 모델을 사용한다.

목표:

- 같은 room/stream shard의 batch publish와 `XACK`를 동시에 여러 fanout worker가 수행하지 않게 한다.
- fanout worker 장애 시 TTL 만료 후 다른 worker가 owner를 획득해 pending entry를 재처리한다.
- HPA로 worker pod를 늘릴 때 같은 방 내부 record 병렬화가 아니라 여러 방의 owner를 worker들에 분산한다.

권장 key/value:

```text
key:   chat:fanout:owner:room:<roomId>:shard:<streamShard>
value: <workerId>:<leaseToken>
ttl:   10000ms 기본값
```

권장 명령:

- 획득: `SET key value NX PX <ttlMillis>`
- 갱신: Lua script로 현재 value가 `<workerId>:<leaseToken>`과 같을 때만 `PEXPIRE`
- 해제: Lua script로 현재 value가 `<workerId>:<leaseToken>`과 같을 때만 `DEL`
- publish 직전/ack 직전 확인: `GET key` 결과가 자신의 token과 다르면 stale owner로 간주하고 publish/ack 중단

`leaseToken`은 fencing token이다. worker가 GC pause나 네트워크 지연으로 TTL을 잃은 뒤 뒤늦게 깨어나도, 새 owner가 만든 token과 다르면 기존 worker는 더 이상 publish/ack를 수행하지 않는다.

fanout owner lease key는 단일 key 명령과 단일 key Lua script만 사용하므로 hash tag가 필수는 아니다. stream key와 lease key를 하나의 multi-key Lua script로 묶어야 하는 설계를 선택할 때만 같은 slot hash tag를 재검토한다.

## 3. Hash Tag가 필요한 경우

다음 조건 중 하나가 생기면 hash tag 설계를 별도 검토한다.

- 하나의 Lua script에서 여러 Redis key를 동시에 읽거나 쓴다.
- `MULTI/EXEC` transaction으로 여러 key를 함께 갱신한다.
- Redis command 자체가 같은 slot의 여러 key를 요구한다.
- sequence key와 stream key를 한 번의 원자 연산으로 묶어야 한다.

예시:

```text
chat:{room:<roomId>}:seq
chat:{room:<roomId>}:stream:shard:<shardNo>
chat:{room:<roomId>}:presence:gateways
```

이 방식은 같은 room 관련 key를 같은 hash slot에 묶을 수 있다. 하지만 very hot room은 특정 slot에 트래픽이 집중될 수 있으므로, hot room에서는 room 단위 hash tag가 오히려 병목이 될 수 있다.

### 3.1 Message Admission Policy

Phase 6 message admission은 room rate limit, user rate limit, slow mode를 하나의 Redis Lua script에서 원자 처리한다.

```text
KEYS[1] chat:admission:room:{<roomId>}:rate:room:<epochSecond>
KEYS[2] chat:admission:room:{<roomId>}:rate:user:<userId>:<epochSecond>
KEYS[3] chat:admission:room:{<roomId>}:slow:user:<userId>
```

이 세 key는 같은 room의 메시지 수락 여부를 함께 결정하므로 `{<roomId>}` hash tag로 같은 Redis Cluster slot에 묶는다. 구현은 먼저 slow mode와 각 counter의 현재 값을 확인하고, 모두 허용될 때만 room/user counter를 증가시키고 slow mode TTL key를 설정한다.

이유:

- room limit은 통과했지만 user limit에서 거부되는 경우 같은 메시지가 room counter만 소모하는 부분 차감 문제를 피한다.
- slow mode에 걸린 메시지는 room/user counter를 소모하지 않는다.
- Redis script 실패 시 메시지 수락은 fail-closed로 거부하고 `chat.message.admission.rejected{reason="redis_error"}` metric을 남긴다.

주의할 점은 very hot room 하나가 특정 Redis slot을 압박할 수 있다는 것이다. 그럼에도 Phase 6에서는 순서와 수락 정책 원자성을 우선한다. 해당 slot이 병목이 되면 방별 정책 튜닝, slow mode 강화, 별도 sequencer/rate-limit service, 또는 room lane 분할을 Phase 7 이후 검토한다.

### 3.2 Room Traffic Snapshot

Phase 6 자동 downgrade는 수락된 메시지 수를 별도 traffic counter에 기록한다.

```text
chat:room-traffic:{<roomId>}:sec:<epochSecond>
chat:room-traffic:active-rooms
```

traffic counter는 accepted message가 Redis Streams append에 성공한 뒤 best-effort로 증가한다. `room-policy` worker는 최근 `60초` window의 counter를 읽어 현재 초당 메시지 수와 1분 p95를 계산하고, `room_storage_configs`에 heat/live feed/rate/slow-mode 정책을 반영한다.

traffic counter는 room 단위 조회 시 여러 초의 key를 한 번에 읽기 때문에 `{<roomId>}` hash tag를 사용한다. active room sorted set은 전체 active room index라 단일 공통 key로 유지하고 score cleanup으로 lifecycle을 관리한다.

## 4. WebSocket Ticket Rate Limit 원자성 판단

현재 WebSocket ticket 발급 rate limit은 user 기준과 IP 기준을 각각 단일 key Lua script로 실행한다.

```text
1. user rate limit script: chat:ws-ticket:rate:user:<userId>
2. IP rate limit script:   chat:ws-ticket:rate:ip:<sha256(ip)>
```

이 구조는 Redis Cluster cross-slot 문제가 없다. 두 제한을 하나의 완전 원자 연산으로 묶지는 않지만, 운영 기본값으로는 충분한 방향이다.

- user limit 통과 후 IP limit에서 거부되면 user counter가 증가할 수 있다.
- Redis script 실패 시 ticket 발급은 fail-closed로 실패한다.
- 이 구조는 abuse 방어 관점에서는 보수적이다. 일부 정상 사용자에게 rate limit이 더 빨리 걸릴 수는 있지만, 제한 우회로 이어지지는 않는다.

Phase 7에서는 다음 운영 지표를 보고 user/IP limit의 완전 원자성이 필요한지 판단한다.

- IP limit 거부 중 user limit counter가 과도하게 소모되어 정상 reconnect가 실패하는 비율
- NAT, corporate proxy, mobile carrier 환경에서 동일 IP 사용자들이 받는 rate limit 영향
- `chat.websocket.ticket.issue.latency` p95/p99
- `chat.websocket.ticket.rate_limit.script.failures` 발생률
- ticket issue failure 중 `rate_limited_user`, `rate_limited_ip`, `failure` 비율
- abuse traffic에서 현재 순차 fail-closed 방식으로 충분히 방어되는지 여부

판단 기준은 다음과 같이 고정한다.

| 단계 | 운영 기준 | 판단 |
| --- | --- | --- |
| 유지 | 정상 reconnect ticket 발급 성공률이 rolling 15분 기준 `99.9%` 이상이고, rate limit으로 인한 정상 reconnect 실패율이 `0.1%` 이하 | 현재 단일 key Lua script + fail-closed 유지 |
| 유지 | NAT/proxy/mobile carrier cohort의 정상 reconnect rate limit 실패율 p95가 `0.3%` 이하 | 현재 정책 유지 |
| 튜닝 | 전체 정상 reconnect rate limit 실패율이 `0.1%` 초과 `0.5%` 이하이거나, 특정 cohort p95가 `0.3%` 초과 `1.0%` 이하 | user/IP limit, window, burst 허용값을 먼저 조정 |
| 튜닝 | IP limit 거부 후 user counter 보수적 소모로 추정되는 실패가 정상 reconnect 시도 중 `0.05%` 초과 `0.2%` 이하 | 완전 원자성보다 정책 튜닝과 burst 허용을 먼저 적용 |
| 완전 원자성 검토 | 정책 튜닝 후에도 전체 정상 reconnect rate limit 실패율이 `0.5%`를 넘는 구간이 rolling 15분 기준 2회 이상 반복 | multi-key 원자 처리 또는 별도 rate limit service 설계 검토 |
| 완전 원자성 검토 | 정책 튜닝 후에도 특정 NAT/proxy/mobile carrier cohort의 실패율 p95가 `1.0%`를 넘는 구간이 rolling 15분 기준 2회 이상 반복 | multi-key 원자 처리 또는 cohort별 정책 분리 검토 |
| 완전 원자성 검토 | IP limit 거부 후 user counter 보수적 소모로 추정되는 실패가 정상 reconnect 시도 중 `0.2%`를 넘는 구간이 rolling 15분 기준 2회 이상 반복 | ticket 발급 성공 건만 user/IP counter를 함께 반영하는 구조 검토 |
| 구조 변경 보류 | abuse test에서 현재 순차 fail-closed 방식이 user/IP 제한 우회를 허용하지 않고, UX 기준도 위 유지 조건을 만족 | 완전 원자성 도입 보류 |

- 정상 사용자 reconnect 실패가 의미 있게 증가하지 않으면 현재 단일 key script 방식을 유지한다.
- IP limit으로 인한 user counter 보수적 소모가 UX 문제를 만들면 limit 정책, window, burst 허용을 먼저 조정한다.
- 그래도 “ticket 발급 성공 건만 user/IP counter를 함께 차감해야 한다”는 운영 요구가 생기면 multi-key 원자 처리 또는 별도 rate limit service를 검토한다.

정상 reconnect 실패율 계산에서 명백한 abuse, invalid session, malformed ticket, 수동 chaos kill 실험은 제외한다. 대신 chaos test 결과는 별도 패널로 보고, 배포/장애 상황에서도 정상 reconnect ticket 발급 성공률이 기준을 만족하는지 확인한다.

## 5. 도메인별 권장 판단

| 도메인 | 현재 판단 | 이유 |
| --- | --- | --- |
| Sequence | 단일 key `INCR 1` 유지 | 같은 방에서는 WebSocket Gateway가 달라도 서버 수락 순서대로 `roomSeq`를 발급해야 함 |
| Presence | 단일 key TTL 갱신 유지 | user/room/gateway index를 한 트랜잭션으로 묶지 않아도 복구 가능 |
| Ticket consume | 단일 key `GETDEL` 유지 | ticket 원문 hash key 1개만 원자 consume하면 충분 |
| Ticket rate limit | user/IP 각각 단일 key Lua script 유지 | cross-slot 없음, fail-closed, 보수적 overcount 허용 |
| Message admission | room 단위 hash tag multi-key Lua script | room/user rate limit과 slow mode를 한 메시지 단위로 원자 처리해야 함 |
| Room traffic snapshot | room 단위 hash tag counter + active room ZSET | 자동 downgrade가 accepted traffic 기준으로 동작해야 함 |
| Redis Streams | shard별 stream key 유지 | hot room 분산이 더 중요하고 multi-key stream transaction을 기본으로 두지 않음 |
| Fanout owner lease | room/stream shard별 단일 key TTL lease 유지 | 같은 방 fanout publish 순서를 보장하면서 worker 장애 시 TTL takeover 가능 |

## 6. 복잡도

- 단일 key Redis command 시간 복잡도: `O(1)` 또는 자료구조 command 특성에 따름
- 단일 key Lua script 시간 복잡도: `O(1)`
- key naming 정책 자체의 공간 복잡도: `O(1)`
- 실제 Redis 공간 복잡도: `O(activePresence + activeTickets + activeRateLimitKeys + activeRoomTrafficKeys + activeFanoutOwnerLeases + streamRetention)`
- message admission Redis 공간 복잡도: `O(activeRoomSecondBuckets + activeUserSecondBuckets + activeSlowModeUsers)`
- room traffic Redis 공간 복잡도: `O(activeRoomTrafficWindowSeconds + activeRooms)`

## 7. 주의사항

> - `{}`를 무심코 placeholder로 쓰면 Redis Cluster hash tag로 해석될 수 있다. 문서 placeholder는 `<...>`를 사용한다.
> - 모든 room key를 `{room:<roomId>}`로 묶으면 hot room 하나가 특정 slot을 압박할 수 있다.
> - multi-key 원자성이 필요한 순간에는 hash slot, hot slot, 장애 복구, latency를 함께 재평가해야 한다.
> - ticket, session token, IP 원문은 Redis key에 직접 넣지 않는다.
> - fanout owner lease는 exactly-once delivery를 보장하지 않는다. publish 후 ack 전 장애, takeover, client reconnect 상황에서는 중복 delivery가 가능하므로 `messageId` deduplication을 유지한다.
> - message admission key는 의도적으로 같은 room의 key를 한 slot에 묶는다. very hot room에서 Redis slot hotspot이 관측되면 rate limit/slow mode 정책을 먼저 강화하고, 그래도 부족하면 room lane 분할이나 별도 admission service를 검토한다.
> - Redis Cluster node는 `appendfsync everysec`를 사용하므로 node 장애 또는 host crash 시 마지막 fsync 이후 최대 1초의 Redis ingest가 손실될 수 있다. Phase 8.7 gap audit/heartbeat로 감지 경로를 둔다.

## 8. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 단일 key script 유지 | Cluster cross-slot 문제 없음, slot 분산이 자연스러움 | 여러 제한을 완전 원자 처리하지 않음 | 기본안 |
| room 단위 hash tag admission | 한 메시지의 room/user/slow-mode 판정을 원자 처리 가능 | very hot room이 한 Redis slot을 압박할 수 있음 | Phase 6 message admission 기본안 |
| 공통 hash tag | 구현이 단순함 | 특정 slot에 트래픽 집중 | 금지에 가깝게 피함 |
| Redis TTL fanout owner lease | 현재 Redis 기반 구조에서 구현 비용이 낮고 worker 장애 takeover가 단순함 | lease TTL/renew 튜닝, stale owner fencing, 중복 delivery deduplication이 필요함 | Phase 6 production 기본안 |
| 외부 coordinator 기반 lease | lease audit과 fencing token 모델을 더 강하게 가져갈 수 있음 | ZooKeeper/etcd 등 운영 컴포넌트 추가 | Redis lease 한계가 확인될 때 검토 |
| 별도 rate limit service | Redis Cluster 제약을 숨길 수 있음 | 서비스 운영 복잡도 증가 | Phase 7 이후 필요 시 검토 |
