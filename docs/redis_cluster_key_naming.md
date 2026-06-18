# Redis Cluster Key Naming Policy

이 문서는 Redis Cluster 전환을 전제로 sequence, presence, ticket, rate limit key naming과 hash slot 정책을 정리한다.

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
| message user rate limit | `chat:rate:user:<userId>:sec:<epochSecond>` | String counter | 불필요 | 짧은 TTL |
| message room rate limit | `chat:rate:room:<roomId>:sec:<epochSecond>` | String counter | 불필요 | 짧은 TTL |
| room stream shard | `chat:stream:room:<roomId>:shard:<shardNo>` | Redis Stream | 불필요 | retention 정책 |
| fan-out pub/sub channel | `chat:pubsub:room:<roomId>:shard:<shardNo>` | Channel | 불필요 | ephemeral |

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
| Message rate limit | user/room 각각 단일 key script 우선 | hot room slot 집중을 피하고 정책별 조정이 쉬움 |
| Redis Streams | shard별 stream key 유지 | hot room 분산이 더 중요하고 multi-key stream transaction을 기본으로 두지 않음 |

## 6. 복잡도

- 단일 key Redis command 시간 복잡도: `O(1)` 또는 자료구조 command 특성에 따름
- 단일 key Lua script 시간 복잡도: `O(1)`
- key naming 정책 자체의 공간 복잡도: `O(1)`
- 실제 Redis 공간 복잡도: `O(activePresence + activeTickets + activeRateLimitKeys + streamRetention)`

## 7. 주의사항

> - `{}`를 무심코 placeholder로 쓰면 Redis Cluster hash tag로 해석될 수 있다. 문서 placeholder는 `<...>`를 사용한다.
> - 모든 room key를 `{room:<roomId>}`로 묶으면 hot room 하나가 특정 slot을 압박할 수 있다.
> - multi-key 원자성이 필요한 순간에는 hash slot, hot slot, 장애 복구, latency를 함께 재평가해야 한다.
> - ticket, session token, IP 원문은 Redis key에 직접 넣지 않는다.

## 8. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 단일 key script 유지 | Cluster cross-slot 문제 없음, slot 분산이 자연스러움 | 여러 제한을 완전 원자 처리하지 않음 | 기본안 |
| room/user 단위 hash tag | multi-key 원자 처리 가능 | hot slot 위험, key policy 복잡도 증가 | 필요 시 제한적으로 사용 |
| 공통 hash tag | 구현이 단순함 | 특정 slot에 트래픽 집중 | 금지에 가깝게 피함 |
| 별도 rate limit service | Redis Cluster 제약을 숨길 수 있음 | 서비스 운영 복잡도 증가 | Phase 7 이후 필요 시 검토 |
