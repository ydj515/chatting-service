# Phase 6 Fanout Owner Lease Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Redis TTL lease 기반 방별 fanout owner를 구현하고, bounded live feed / admin room policy override / Redis admission policy를 함께 묶어 fanout worker를 여러 대로 늘려도 같은 방 live feed 순서와 운영 제어가 깨지지 않게 한다.

**Architecture:** `FanoutOwnerLeaseService` 인터페이스와 Redis 구현체를 추가한다. `HotRoomFanoutWorker`는 stream key별 owner lease를 획득한 경우에만 read/claim/publish/ack를 수행하고, publish 직전과 ack 직전에 fencing token을 검증한다. 메시지 수락 경로는 `room_storage_configs`의 방 정책을 캐시해 읽고, Redis Cluster hash tag를 사용한 room 단위 Lua script로 room/user rate limit과 slow mode를 원자 처리한다.

**Tech Stack:** Kotlin, Spring Boot configuration properties, Spring Data Redis `RedisTemplate`, Redis `SET NX PX`, Redis Lua script, JUnit 5, Mockito, Micrometer.

---

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
- hot room에서 클라이언트가 무한 메시지를 렌더링하지 않도록 bounded live feed를 적용한다.
- 운영자가 방별 live feed window, room/user rate limit, slow mode를 admin API로 override하고 audit log를 남길 수 있어야 한다.

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
- message admission policy는 room/user/slow-mode 키를 `chat:admission:room:{roomId}:...` hash tag로 같은 Redis Cluster slot에 묶어 Lua 하나에서 처리한다. 이렇게 해야 room limit은 통과했지만 user limit에서 거부되는 경우 같은 메시지의 counter가 부분 소모되는 문제를 피할 수 있다.
- `room_storage_configs`는 방별 운영 정책의 source of truth이고, 메시지 수락 경로에서는 짧은 TTL cache와 admin update 시 evict를 함께 사용해 DB hot path를 피한다.

## 현재 구현 상태

- Redis TTL fanout owner lease 구현 완료.
- `HotRoomFanoutWorker` owner gate와 publish/ack 전 fencing token 검증 구현 완료.
- 클라이언트 bounded live feed 기본값 `maxMessages=1000`, `maxAgeSeconds=60` 구현 완료.
- admin room policy override API와 audit log 구현 완료.
- Redis Lua 기반 message admission policy 구현 완료.
- WebSocket admission reject는 `MESSAGE_ADMISSION_REJECTED` error event로 응답하도록 구현 완료.
- accepted room traffic counter와 `RoomHeatClassifier`, `room-policy` worker 기반 자동 downgrade 구현 완료.
- `RoomPolicyWorker`는 traffic snapshot에 `RoomPolicySignalProvider`의 writer/fanout/gateway lag 신호를 합성한 뒤 downgrade를 판단한다. 현재 기본 provider는 0 값을 반환하므로 실제 Redis Streams lag와 Gateway queue depth 계측은 provider 구현을 교체해 붙인다.
- fanout owner lease의 `renewIntervalMillis=3000`은 단순 설정값이 아니라 실제 renew cadence로 적용한다. 기존 구현은 owner를 보유 중인 worker가 매 poll마다 TTL renew Lua를 호출해 3초 운영 기준이 코드에서 의미를 잃는 문제가 있었으므로, lease별 마지막 renew 시각을 저장하고 interval이 지난 경우에만 renew한다.
- admin room policy override는 기본적으로 `autoPolicyEnabled=false`를 저장한다. 기존 자동 downgrade upsert는 admin이 수동으로 줄인 live feed/rate/slow-mode 정책을 다음 `room-policy` worker poll에서 다시 덮어쓸 수 있었으므로, 자동 upsert는 `auto_policy_enabled=true`인 방만 갱신한다.
- `moderatorPriority`는 admission policy에 연결했다. 기본값은 true이며, 방별로 false로 끄면 `OWNER/ADMIN`도 room/user rate limit과 slow mode 제한 대상이 된다.
- Redis cache value serializer는 `GenericJackson2JsonRedisSerializer` default typing을 켠다. 실제 multi-worker smoke 중 `RoomAdmissionPolicy`가 Redis cache를 거치며 `LinkedHashMap`으로 복원되어 WebSocket send path에서 `ClassCastException`이 발생했으므로, `roomAdmissionPolicies` cache가 DTO 타입을 보존하도록 회귀 테스트를 추가했다.
- Phase 6 검증용 `scripts/load-chat.mjs`를 추가했다. `--assert-room-seq-order` 옵션은 viewer live feed에서 받은 `CHAT_MESSAGE`/`CHAT_MESSAGE_BATCH`의 `roomSeq` 역전을 검출한다. 초깃값은 수신한 sample만 검사해 200개 중 1개만 받아도 통과할 수 있었으므로, `--min-received-ratio` 옵션을 추가해 viewer별 fanout 수신량도 함께 검증한다. worker kill takeover 검증처럼 TTL 만료 동안 쌓인 backlog가 큰 batch로 fanout되는 경우를 위해 `--drain-wait`와 WebSocket continuation frame decoder도 추가했다.
- `scripts/phase6-fanout-takeover-smoke.mjs`를 추가했다. 이 스크립트는 Compose worker replica 2개를 확인하고, `load-chat`가 생성한 방의 Redis owner lease key를 조회해 실제 owner container를 kill한 뒤, TTL 10초 만료 후 다른 worker가 takeover해 viewer별 수신량과 `roomSeq` 순서를 만족하는지 검증한다.
- Docker Compose의 `chat-worker-app-1`은 `container_name`/고정 `hostname`을 제거해 `docker compose up -d --scale chat-worker-app-1=2`로 fanout worker replica를 늘릴 수 있게 했다.
- 검증 완료: `./gradlew test --no-daemon`, Node contract tests, frontend unit/build, `git diff --check`, Compose multi-worker smoke, owner kill takeover smoke. 로컬 Docker image build는 Buildx bake가 장시간 정체되어 host Java 17 bootJar를 running container에 교체하는 방식으로 runtime smoke를 수행했다.
- owner kill takeover smoke 결과: `node scripts/phase6-fanout-takeover-smoke.mjs --room phase6 --viewers 3 --messages-per-sec 20 --duration 30 --kill-after 5 --drain-wait 12`가 room `14`에서 owner container `chatting-service-chat-worker-app-1-1`을 kill했고, `sent=600`, `receivedPerViewer=[600,600,600]`, `assertedRoomSeqOrder=true`로 통과했다.

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
  - renew interval은 Redis 호출량과 takeover latency의 균형점이다. TTL 10초 / renew 3초 기준에서는 worker가 정상 poll 중일 때 3초마다 TTL을 연장하고, stale owner는 publish/ack 직전 token validation에서 차단한다.

5. 관측 지표 추가
   - `chat.fanout.owner.lease.acquire`
   - `chat.fanout.owner.lease.renew`
   - `chat.fanout.owner.lease.lost`
   - `chat.fanout.owner.rooms`
   - `chat.fanout.owner.takeovers`
   - `chat.fanout.owner.token_mismatch`

6. 자동 downgrade 추가
   - Streams append 성공 후 accepted room traffic counter 기록
   - `room-policy` worker가 active room의 최근 60초 traffic snapshot을 계산
   - `RoomHeatClassifier`가 `NORMAL`, `HOT`, `VERY_HOT`, `OVERLOAD` 정책 산출
   - `room_storage_configs`에 heat/live feed/rate/slow-mode 정책 자동 upsert

7. 검증 시나리오 추가
   - fanout worker 2대 이상 실행
   - 같은 방 사용자 3명이 서로 다른 Gateway에 붙은 상태로 메시지 전송
   - worker kill 후 TTL takeover 확인
   - lease token mismatch 상황에서 stale owner가 publish/ack하지 않는지 확인
   - 클라이언트 화면과 DB history가 `roomSeq` 순서로 일치하는지 확인

## 구현 작업 체크리스트

### Task 1: 설정과 key parsing

**Files:**
- Modify: `chat-persistence/src/main/kotlin/config/ChatWorkerProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/redis/MessageStreamKeyResolver.kt`
- Test: `chat-persistence/src/test/kotlin/service/FanoutOwnerLeaseServiceTest.kt`

- [x] **Step 1: Write failing tests**
  - `ChatWorkerProperties().fanout.ownerLease.enabled`는 `true`
  - TTL 기본값은 `10000`
  - renew interval 기본값은 `3000`
  - key prefix 기본값은 `chat:fanout:owner:room:`
  - `MessageStreamKeyResolver.parseRoomStreamKey("chat:stream:room:10:shard:2")`는 `roomId=10`, `streamShard=2`를 반환

- [x] **Step 2: Run test to verify RED**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.FanoutOwnerLeaseServiceTest --no-daemon`
  - Expected: compile failure 또는 missing property/function failure

- [x] **Step 3: Implement minimal config and parser**
  - `ChatWorkerProperties.StreamConsumer`에 `ownerLease: FanoutOwnerLease = FanoutOwnerLease()` 추가
  - `FanoutOwnerLease(enabled=true, ttlMillis=10000, renewIntervalMillis=3000, keyPrefix="chat:fanout:owner:room:")` 추가
  - `MessageStreamKeyResolver.RoomStreamKey(roomId, streamShard)`와 `parseRoomStreamKey(streamKey)` 추가

- [x] **Step 4: Run test to verify GREEN**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.FanoutOwnerLeaseServiceTest --no-daemon`
  - Expected: PASS

### Task 2: Redis fanout owner lease service

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/FanoutOwnerLeaseService.kt`
- Test: `chat-persistence/src/test/kotlin/service/FanoutOwnerLeaseServiceTest.kt`

- [x] **Step 1: Write failing tests**
  - `acquire(roomId=10, streamShard=0)`는 Redis `SET NX PX`를 `chat:fanout:owner:room:10:shard:0`, `<workerId>:<token>`, `10000ms`로 호출한다.
  - 이미 owner가 있으면 `null`을 반환하고 fanout read를 허용하지 않는다.
  - 같은 worker가 기존 lease를 가지고 있으면 token 비교 Lua script로 renew한다.
  - `validate(lease, BEFORE_PUBLISH)`는 Redis value가 token과 다르면 false를 반환하고 token mismatch metric을 남긴다.
  - `enabled=false`이면 Redis를 호출하지 않고 disabled lease를 반환한다.

- [x] **Step 2: Run test to verify RED**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.FanoutOwnerLeaseServiceTest --no-daemon`
  - Expected: missing `FanoutOwnerLeaseService` failure

- [x] **Step 3: Implement minimal service**
  - Interface: `FanoutOwnerLeaseService`
  - Data: `FanoutOwnerLease`
  - Enum: `FanoutOwnerLeaseValidationStage`
  - Bean: `RedisFanoutOwnerLeaseService`
  - Lua: renew and release use compare-and-expire/delete
  - Metrics: acquire, renew, lost, token mismatch, owned rooms gauge

- [x] **Step 4: Run test to verify GREEN**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.FanoutOwnerLeaseServiceTest --no-daemon`
  - Expected: PASS

### Task 3: HotRoomFanoutWorker owner gate

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/HotRoomFanoutWorker.kt`
- Test: `chat-persistence/src/test/kotlin/service/HotRoomFanoutWorkerTest.kt`

- [x] **Step 1: Write failing tests**
  - owner를 획득하지 못한 stream key는 `readNew`, `claimPending`, broadcast, ack를 수행하지 않는다.
  - publish 직전 token validation이 실패하면 broadcast와 ack를 수행하지 않는다.
  - publish 후 ack 직전 token validation이 실패하면 broadcast는 수행했지만 ack는 수행하지 않는다.

- [x] **Step 2: Run test to verify RED**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.HotRoomFanoutWorkerTest --no-daemon`
  - Expected: owner gate가 없어서 read/broadcast/ack가 발생하는 failure

- [x] **Step 3: Implement owner gate**
  - stream key를 `MessageStreamKeyResolver.parseRoomStreamKey()`로 파싱
  - stream key별 `fanoutOwnerLeaseService.acquire(roomId, streamShard)` 호출
  - acquired stream key만 consumer group ensure/read/claim 대상
  - batch publish 전 `validate(BEFORE_PUBLISH)`
  - ack 또는 DLQ ack 전 `validate(BEFORE_ACK)`

- [x] **Step 4: Run test to verify GREEN**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.HotRoomFanoutWorkerTest --no-daemon`
  - Expected: PASS

### Task 4: runtime config and docs sync

**Files:**
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Modify: `.env.example`
- Modify: `docs/configuration.md`
- Modify: `docs/observability_metrics.md`

- [x] **Step 1: Add runtime config**
  - `chat.worker.fanout.owner-lease.enabled=${CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED:true}`
  - `chat.worker.fanout.owner-lease.ttl-millis=${CHAT_WORKER_FANOUT_OWNER_LEASE_TTL_MILLIS:10000}`
  - `chat.worker.fanout.owner-lease.renew-interval-millis=${CHAT_WORKER_FANOUT_OWNER_LEASE_RENEW_INTERVAL_MILLIS:3000}`
  - `chat.worker.fanout.owner-lease.key-prefix=${CHAT_WORKER_FANOUT_OWNER_LEASE_KEY_PREFIX:chat:fanout:owner:room:}`

- [x] **Step 2: Remove stale sequence block config**
  - Delete `CHAT_MESSAGE_SEQUENCE_BLOCK_SIZE` from `.env.example`
  - Keep `CHAT_MESSAGE_SEQUENCE_TTL`

- [x] **Step 3: Verify docs and config**
  - Run: `rg -n 'CHAT_MESSAGE_SEQUENCE_BLOCK_SIZE|Sequence Blocks|Redis로부터 시퀀스 블록' . docs chat-runtime-config -S`
  - Expected: no stale active config reference

### Task 5: verification

**Files:**
- No new files

- [x] **Step 1: Targeted backend tests**
  - Run: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.FanoutOwnerLeaseServiceTest --tests com.chat.persistence.service.HotRoomFanoutWorkerTest --no-daemon`
  - Expected: PASS

- [x] **Step 2: Worker/application regression**
  - Run: `./gradlew :chat-persistence:test :chat-worker-application:test --no-daemon`
  - Expected: PASS

- [x] **Step 3: Full verification**
- Run: `./gradlew test --no-daemon`
- Run: `node --test scripts/lib/loadChatPlan.test.mjs`
- Run: `node --check scripts/load-chat.mjs`
- Run: `node --check scripts/phase6-fanout-takeover-smoke.mjs`
- Run: `CHAT_HTTP_URL=http://localhost:18081/api CHAT_WS_URL=ws://localhost:18081/api/ws/chat node scripts/phase6-fanout-takeover-smoke.mjs --room phase6 --viewers 3 --messages-per-sec 20 --duration 30 --kill-after 5 --drain-wait 12`
- Run: `git diff --check`
- Expected: PASS and no whitespace errors

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
> - admin override를 적용한 방은 `autoPolicyEnabled=true`로 다시 열기 전까지 자동 downgrade worker가 live feed/rate/slow-mode 정책을 덮지 않는다.
> - 현재 slow client 보호는 오래된 pending batch를 별도 gap event로 보내기보다, bounded outbound queue overflow 시 해당 WebSocket을 `1013 Outbound queue full`로 닫아 재연결과 history/gap fill 경로를 사용하게 하는 방식이다.
> - subscriber-only mode는 아직 subscriber 도메인/역할이 없어 강제 로직으로 구현하지 않았다. 현재 `MemberRole`은 `OWNER`, `ADMIN`, `MEMBER`만 가진다.
> - 시스템 lag 기반 `OVERLOAD` 판정은 `RoomPolicySignalProvider` 주입 경로까지 구현했다. 기본 provider는 0을 반환하므로 production에서는 Redis Streams lag와 Gateway send queue depth를 제공하는 provider를 연결해야 한다.
> - takeover smoke에서 TTL 10초 동안 쌓인 backlog는 큰 `CHAT_MESSAGE_BATCH`로 전파될 수 있다. 검증 클라이언트가 WebSocket continuation frame을 처리하지 않으면 서비스가 정상 fanout해도 수신량을 낮게 오판할 수 있으므로, raw WebSocket load client는 continuation frame join을 지원해야 한다.
> - 로컬 검증은 Docker image clean rebuild가 아니라 Java 17 `bootJar`를 running container에 교체해 수행했다. release 전 CI 또는 clean Docker build에서 동일 smoke를 다시 실행해야 한다.

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
