# Phase 6 Fanout Owner Lease Plan

작성일: 2026-06-18

## 문제 이해 / 요구사항 정리

조건:

- 이 서비스는 트위치 같은 스트리밍 플랫폼의 채팅 서비스를 목표로 한다.
- 같은 방의 live feed는 사용자가 보낸 순서, 정확히는 서버가 수락한 `roomSeq` 순서를 따라야 한다.
- Phase 3에서 Redis Streams 기반 `HotRoomFanoutWorker`가 도입되었다.
- Phase 5 이후 production을 생각하면 fanout worker를 여러 replica로 늘려야 한다.
- Redis Streams consumer group은 record 중복 분배를 줄여주지만, 같은 방의 batch publish 순서를 보장하지 않는다.
- 방 `id=3`에서는 Gateway별 sequence block 선할당 때문에 실제 생성 시간은 `room_seq=1001..1016` 이후 `room_seq=46..53`이었지만, 클라이언트 정렬 결과는 46..53이 먼저 표시되는 문제가 확인되었다.

목표:

- fanout worker를 여러 대로 늘려도 같은 room/stream shard에서 batch publish 순서가 뒤집히지 않게 한다.
- Redis 기반 현재 구조를 유지하면서 production에서 적용 가능한 ownership 모델을 둔다.
- worker 장애, GC pause, Redis reconnect 상황에서도 owner takeover와 pending 재처리가 가능해야 한다.
- writer scale-out과 fanout scale-out의 안전 조건을 문서와 코드에서 분리한다.

## 선택한 접근

선택한 접근:

- Redis TTL lease 기반 방별 fanout owner를 Phase 6의 첫 production gate로 둔다.
- owner key는 `chat:fanout:owner:room:<roomId>:shard:<streamShard>`를 사용한다.
- owner value는 `<workerId>:<leaseToken>`으로 저장한다.
- owner 획득은 `SET NX PX <ttlMillis>`로 처리한다.
- owner renew/release는 현재 value가 자신의 token과 일치할 때만 동작하는 Lua script로 처리한다.
- `HotRoomFanoutWorker`는 owner일 때만 `XREADGROUP`, pending claim, batch publish, `XACK`를 수행한다.
- publish 직전과 ack 직전에 owner token을 다시 확인한다.

이유:

- consumer group만으로 fanout worker를 늘리면 같은 방의 record가 여러 worker에 분산되어 batch 간 publish 순서가 뒤집힐 수 있다.
- Redis TTL lease는 현재 인프라에 이미 있는 Redis로 구현 가능하고, 별도 coordinator를 추가하지 않아도 worker death takeover가 가능하다.
- fencing token을 두면 GC pause나 network stall로 lease를 잃은 stale owner가 뒤늦게 publish/ack하는 것을 막을 수 있다.

## 구현 계획

1. `FanoutOwnerLeaseService` 추가
   - acquire: `SET key value NX PX ttl`
   - renew: token 비교 Lua 후 `PEXPIRE`
   - release: token 비교 Lua 후 `DEL`
   - validate: `GET key` 값이 현재 token과 일치하는지 확인

2. `HotRoomFanoutWorker` owner gate 적용
   - room/stream shard별 owner 획득 후에만 stream read
   - publish 직전 token validation
   - `XACK` 직전 token validation
   - token mismatch 시 publish/ack 중단, lease lost metric 기록

3. pending claim 정책 조정
   - owner worker만 pending claim 수행
   - owner takeover 후 `minIdleMillis`가 지난 record만 claim
   - DLQ 정책은 기존 delivery count 기준 유지

4. 설정 추가
   - `CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED=true`
   - `CHAT_WORKER_FANOUT_OWNER_LEASE_TTL_MILLIS=10000`
   - `CHAT_WORKER_FANOUT_OWNER_LEASE_RENEW_INTERVAL_MILLIS=3000`
   - `CHAT_WORKER_FANOUT_OWNER_LEASE_KEY_PREFIX=chat:fanout:owner:room:`

5. 관측 지표 추가
   - `chat.fanout.owner.lease.acquire`
   - `chat.fanout.owner.lease.renew`
   - `chat.fanout.owner.lease.lost`
   - `chat.fanout.owner.rooms`
   - `chat.fanout.owner.takeovers`
   - `chat.fanout.owner.token_mismatch`

6. 검증 시나리오 추가
   - fanout worker 2대 이상 실행
   - 같은 방 사용자 3명이 서로 다른 Gateway에 붙은 상태로 메시지 전송
   - worker kill 후 TTL takeover 확인
   - lease token mismatch 상황에서 stale owner가 publish/ack하지 않는지 확인
   - 클라이언트 화면과 DB history가 `roomSeq` 순서로 일치하는지 확인

## 복잡도

- owner acquire/renew/validate/release 시간 복잡도: `O(1)`
- fanout publish 전후 token 확인 시간 복잡도: 메시지 batch당 `O(1)`
- worker별 owner room 관리 공간 복잡도: `O(ownedRoomShards)`
- Redis owner lease 공간 복잡도: `O(activeRoomShards)`
- 클라이언트 deduplication 공간 복잡도: bounded live feed window 기준 `O(windowMessages)`

## 주의사항

> - Redis TTL lease는 exactly-once delivery를 보장하지 않는다. publish 후 ack 전 장애가 나면 takeover worker가 같은 메시지를 다시 publish할 수 있으므로 `messageId` deduplication은 계속 필요하다.
> - TTL이 너무 짧으면 정상 worker가 GC pause나 Redis latency로 lease를 자주 잃고, 너무 길면 장애 takeover가 늦어진다.
> - owner lease 없이 fanout worker replica만 늘리면 같은 방 batch publish 순서가 뒤집힐 수 있다.
> - 같은 방을 여러 lane으로 나누는 설계는 별도 클라이언트 merge 계약이 필요하므로 Phase 6 기본안에서 제외한다.
> - 방 `id=3`의 과거 sequence inversion 데이터는 코드 변경만으로 자동 repair되지 않는다. 검증 방으로 계속 쓸 경우 별도 one-off repair가 필요하다.

## 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Redis TTL owner lease | 현재 Redis 기반 구조에서 구현 비용이 낮고 worker 장애 takeover가 단순함 | TTL/renew 튜닝과 stale owner fencing이 필요함 | production 기본안 |
| Redis Streams consumer group만 사용 | 구현이 가장 단순하고 현재 코드와 가깝다 | 같은 방 batch publish 순서가 뒤집힐 수 있음 | fanout 다중 replica에는 부적합 |
| Kafka partition 기반 fanout | 방/partition 단위 순서 보장이 명확함 | Kafka 미사용 전제와 다르고 운영 컴포넌트가 증가함 | 장기 대안 |
| 전용 sequencer/fanout owner service | ownership, fencing, audit를 강하게 통제 가능 | 서비스 추가와 장애 지점 증가 | Redis lease 한계가 확인될 때 검토 |

## 완료 기준

- 같은 room/stream shard에 active fanout owner가 동시에 2개 이상 생기지 않는다.
- fanout worker를 2개 이상 띄워도 같은 방의 `CHAT_MESSAGE_BATCH`가 `roomSeq` 순서로 발행된다.
- owner worker kill 후 TTL 만료와 takeover로 fanout이 재개된다.
- stale owner는 token mismatch를 감지하고 publish/ack를 수행하지 않는다.
- 관련 설정, Redis key naming, observability metric, Q&A, architecture 문서가 같은 production 결정을 설명한다.
