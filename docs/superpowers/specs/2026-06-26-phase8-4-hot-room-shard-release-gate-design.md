# Phase 8.4 Hot Room Shard 분산 + 10k Release Gate 설계서

- 작성일: 2026-06-26
- 슬라이스: Phase 8.4 hot room shard 분산 + 10k msg/sec release gate
- 상태: 설계 승인
- 구현 브랜치: `feat/phase8-4-hot-room-shard-release-gate`
- 계획 문서: `docs/superpowers/plans/2026-06-26-phase8-4-hot-room-shard-release-gate.md`

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 8의 목표는 Kubernetes 전환 전에 Docker Compose 기준 공개 트래픽 운영 토대를 확보하는 것이다.
- Phase 8.1은 Prometheus/Grafana 관측 파이프라인과 Gateway/Fan-out metric을 완료했다.
- Phase 8.2는 Docker backend Redis topology를 3 master + 3 replica Redis Cluster로 전환했다.
- Phase 8.3은 MinIO 기반 S3 호환 Object Storage와 cold archive 경로를 추가했다.
- production readiness 평가의 B-4는 hot room shard가 실제로 분산되지 않는 문제다.
- 현재 `ChatServiceImpl.SHARD_COUNT`가 `1`로 고정되어 `streamShard`, `writeShard`, `fanoutShard`가 모두 `0`으로 계산된다.
- `RoomStorageConfigJdbcRepository.currentShardCount()`는 `room_storage_configs.current_shard_count`를 읽지만 메시지 수락 경로에서 사용되지 않는다.
- `room_storage_configs` DDL에는 `current_shard_count`와 `fanout_shard_count`가 이미 존재한다.
- `HotRoomFanoutWorker`와 `FanoutOwnerLeaseService`는 stream key의 `roomId`와 `streamShard`를 파싱해 stream shard별 owner lease를 획득할 수 있다.
- `MessageWriterWorker`는 known stream set을 동적으로 읽으므로 새 stream shard가 생기면 consumer group 생성 후 읽을 수 있다.
- 클라이언트 live feed는 `messageId` 중복 제거와 `roomSeq` 정렬을 이미 수행한다.
- hot room 기본 shard count는 사용자가 승인한 값으로 고정한다.
  - `HOT = 16`
  - `VERY_HOT = 64`

### 목표

- 방별 shard 설정을 메시지 수락 경로의 source of truth로 사용한다.
- hot room 자동 승격 시 `room_storage_configs.current_shard_count`와 `fanout_shard_count`를 늘린다.
- 자동 정책은 shard count를 줄이지 않는다. 한 번 확장된 방은 자동 downgrade로 인해 shard count가 작아지지 않는다.
- 새 메시지는 방별 shard 설정에 따라 `writeShard`, `streamShard`, `fanoutShard`를 계산한다.
- fanout 분산은 단일 방의 메시지를 여러 stream shard로 나누어 여러 fanout owner가 병렬 처리할 수 있게 한다.
- 10,000 msg/sec를 60초 이상 유지하는 부하 검증을 명시적인 release gate 스크립트로 실행한다.
- release gate는 client-visible 결과와 운영 metric을 함께 확인한다.

### 비범위

- Kubernetes HPA, Service, Ingress, dedicated Gateway pool, room-aware routing은 Phase 9 범위다.
- Redis admission Lua script의 room 단위 hash tag 분할은 이번 슬라이스에서 하지 않는다.
- 전역 strict raw arrival ordering은 보장하지 않는다. client-visible ordering은 `roomSeq` 정렬로 보장한다.
- 과거 stream key나 과거 PostgreSQL bucket을 재분배하는 resharding은 하지 않는다.
- 분산 부하 발생기 클러스터 자체는 이번 슬라이스에서 만들지 않는다. 이번 작업은 release gate 스크립트와 판정 기준을 고정한다.
- moderation, ban/mute, token revocation, MAXLEN, gap audit, heartbeat는 각각 Phase 8.5~8.7 범위다.

## 2. 해결 접근

### 선택한 접근

권장안 A를 적용한다.

1. `RoomStorageConfigReader`를 `RoomShardConfig` 기반 reader로 확장한다.
2. `RoomStorageConfigJdbcRepository`는 `current_shard_count`와 `fanout_shard_count`를 함께 읽고 1 미만 값을 `1`로 보정한다.
3. `RoomHeatPolicy`에 `writeShardCount`, `fanoutShardCount`를 추가한다.
4. `RoomHeatClassifier`는 heat level별 shard count를 다음처럼 결정한다.
   - `NORMAL`: `1`
   - `HOT`: `16`
   - `VERY_HOT`: `64`
   - `OVERLOAD`: `64`
5. `RoomPolicyJdbcRepository.applyAutomaticPolicy()`는 자동 정책 upsert 시 shard count를 함께 반영하되, 기존 값보다 작은 값으로 낮추지 않는다.
6. `ChatServiceImpl.sendMessage()`는 sequence 발급 후 방별 shard config를 읽어 새 메시지의 shard를 계산한다.
7. `streamShard`와 `fanoutShard`는 `roomSeq` round-robin 기반으로 계산한다.
8. `writeShard`는 기존 설계대로 `messageId` hash 기반으로 계산한다.
9. `scripts/phase8-hot-room-release-gate.mjs`를 추가해 10k/60초 부하 조건과 Prometheus metric 판정을 release gate로 묶는다.

### 이유

- `roomId % shardCount`는 방 하나가 항상 한 shard에만 매핑되므로 hot room 분산 효과가 없다.
- `roomSeq` round-robin은 같은 방의 메시지를 shard count만큼 균등하게 나눈다.
- `streamShard` 단위 owner lease는 이미 구현되어 있으므로, stream shard만 실제로 늘리면 fanout owner도 여러 worker로 분산될 수 있다.
- `writeShard`와 `fanoutShard`를 분리하면 PostgreSQL 저장 분산과 live fanout 병렬화 요구를 각각 독립적으로 조정할 수 있다.
- 자동 downgrade가 shard count를 줄이지 않게 하면 hot/cold flapping 때문에 stream topology가 계속 바뀌는 문제를 피할 수 있다.
- release gate wrapper를 별도로 두면 일반 개발용 `load-chat.mjs`와 운영 판정용 10k gate의 의미를 분리할 수 있다.

## 3. 설계 상세

### Room shard config

`RoomShardConfig`는 방별 shard 계산에 필요한 최소 상태다.

```kotlin
data class RoomShardConfig(
    val writeShardCount: Int = 1,
    val fanoutShardCount: Int = 1,
)
```

`writeShardCount`는 PostgreSQL `write_shard` 분산에 사용한다. DB source는 `room_storage_configs.current_shard_count`다.

`fanoutShardCount`는 Redis Streams key 분산과 fanout owner 분산에 사용한다. DB source는 `room_storage_configs.fanout_shard_count`다.

row가 없거나 값이 `1`보다 작으면 `1`로 보정한다. hot path DB 조회 부담을 줄이기 위해 `roomShardConfigs` cache를 추가하고 TTL 기본값은 `10s`로 둔다.

### Heat policy shard count

`ChatRoomPolicyProperties`에 shard count 설정을 추가한다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| `hotShardCount` | `16` | `HOT` 방의 write/fanout shard count |
| `veryHotShardCount` | `64` | `VERY_HOT`와 `OVERLOAD` 방의 write/fanout shard count |

`OVERLOAD`는 시스템 lag 또는 gateway queue 초과 상태다. 이 상태에서 shard count를 `1`로 낮추면 병목이 더 커질 수 있으므로 `VERY_HOT`과 같은 `64`를 유지한다.

### Automatic policy upsert

자동 정책은 `room_storage_configs`에 heat/live feed/rate/slow-mode 정책과 shard count를 함께 upsert한다.

자동 정책의 shard count update는 monotonic expansion이다.

```sql
current_shard_count = GREATEST(room_storage_configs.current_shard_count, EXCLUDED.current_shard_count)
fanout_shard_count = GREATEST(room_storage_configs.fanout_shard_count, EXCLUDED.fanout_shard_count)
```

따라서 방이 `VERY_HOT`으로 승격되어 `64` shard가 된 뒤 이후 `NORMAL`로 분류되어도 자동 정책은 shard count를 `1`로 줄이지 않는다. live feed window, rate limit, slow mode는 기존처럼 현재 heat level에 맞춰 갱신한다.

### Message shard assignment

메시지 수락 경로는 다음 순서로 동작한다.

1. 사용자, 방, 멤버십, idempotency, admission policy를 검증한다.
2. `messageId`를 생성한다.
3. Redis sequence에서 `roomSeq`를 발급한다.
4. `RoomStorageConfigReader.shardConfig(roomId)`를 읽는다.
5. `writeShard = hash(messageId) % writeShardCount`를 계산한다.
6. `streamShard = (roomSeq - 1) % fanoutShardCount`를 계산한다.
7. `fanoutShard = streamShard`로 둔다.
8. `MessageStreamEnvelope`에 shard 값을 담아 Redis Streams에 append한다.

`roomSeq` 기반 fanout shard는 순차 메시지를 shard count만큼 round-robin으로 나눈다. 예를 들어 `HOT=16`이면 `roomSeq=1`은 shard `0`, `roomSeq=2`는 shard `1`, `roomSeq=17`은 다시 shard `0`이다.

### Fanout ordering contract

fanout shard가 여러 개가 되면 raw WebSocket frame 도착 순서는 shard별 worker 처리 속도에 따라 뒤집힐 수 있다.

이 슬라이스의 ordering 계약은 다음과 같다.

- 서버는 모든 메시지에 단조 증가하는 `roomSeq`를 부여한다.
- fanout payload는 `messageId`, `roomSeq`, `streamShard`, `fanoutShard`를 포함한다.
- 클라이언트는 `messageId` 기준으로 중복 제거하고 `roomSeq` 기준으로 표시 순서를 정렬한다.
- release gate는 raw delivery diagnostic과 client-visible release blocking 결과를 분리한다.

### Release gate

`scripts/phase8-hot-room-release-gate.mjs`는 운영 판정용 wrapper다.

기본 gate 조건은 다음과 같다.

| 항목 | 기준 |
| --- | --- |
| viewers | `10000` |
| messages/sec | `10000` |
| duration | `60s` |
| min received ratio | `0.9` |
| roomSeq ordering metadata | 모든 fanout/history payload에 `roomSeq`, `streamShard`, `fanoutShard` 포함 |
| stream shard 분산 | hot room 기준 최소 `16`개 stream shard 관측 |
| fanout p95 | `500ms` 이하 |
| Redis Streams group lag | `1000` entries 이하 |

부하 발생은 기존 `scripts/load-chat.mjs`를 재사용하되, release gate wrapper가 인자를 고정하고 결과 JSON과 Prometheus query 결과를 함께 판정한다. Multi-shard fanout은 raw frame 도착 순서를 강제하지 않으므로 Phase 8.4 gate는 raw `--assert-room-seq-order`를 사용하지 않는다.

Prometheus가 없는 환경에서는 release gate wrapper가 실패한다. 8.4는 "실제 release gate"를 만드는 작업이므로, 관측 파이프라인 없이 형식 통과하지 않는다.

### Failure handling

- shard config row가 없으면 `1/1`로 fallback한다.
- shard config DB 조회가 실패하면 메시지 수락을 실패시킨다. 잘못된 shard 설정으로 잘못된 stream key에 append하는 것보다 fail-closed가 안전하다.
- Redis Streams append 실패 시 기존처럼 메시지를 저장하거나 traffic counter를 기록하지 않는다.
- release gate에서 load runner 실패, JSON parse 실패, Prometheus query 실패, threshold 초과 중 하나라도 발생하면 exit code `1`로 종료한다.

## 4. 구현 계획 요약

세부 implementation plan은 별도 계획 문서에 작성한다. 예상 task 묶음은 다음과 같다.

1. Phase 8.4 spec/plan 문서 작성
2. Room shard config reader와 cache 추가
3. Heat classifier와 automatic policy upsert에 shard count 추가
4. 메시지 수락 경로에서 동적 write/fanout shard 계산 적용
5. fanout worker multi-shard owner 분산 회귀 테스트 추가
6. 10k release gate wrapper와 pure test 추가
7. 설정, 운영 문서, dashboard 설명 갱신
8. Kotlin/Node test와 문법 검증 실행

## 5. 복잡도

- 메시지 shard config 조회 시간 복잡도: cache hit 기준 `O(1)`, cache miss 기준 DB 단건 조회 `O(1)`
- 메시지 shard 계산 시간 복잡도: `O(1)`
- 메시지 shard 계산 공간 복잡도: `O(1)`
- room policy 분류 시간 복잡도: `O(1)`
- room policy upsert 시간 복잡도: 방 1개당 DB upsert `O(1)`
- fanout worker stream 탐색 시간 복잡도: known stream 수를 `S`라 할 때 `O(S)`
- release gate 부하 전송 시간 복잡도: 전송 메시지 수를 `M`이라 할 때 `O(M)`
- release gate client-visible 수신 집계 시간 복잡도: 수신 frame 수를 `R`이라 할 때 `O(R)`
- release gate wrapper Prometheus query 시간 복잡도: query 개수를 `Q`라 할 때 `O(Q)`
- release gate wrapper 공간 복잡도: load runner summary 크기와 Prometheus 결과 크기 기준 `O(V + Q)` (`V`는 viewer 수)

## 6. 주의사항

> - `streamShard = roomId % shardCount`는 hot room 분산을 만들지 못한다. 같은 방은 계속 한 shard에만 몰린다.
> - multi-shard fanout은 raw arrival order를 보장하지 않는다. 사용자에게 보이는 순서는 `roomSeq` 기반 client-visible 정렬로 판정해야 한다.
> - 자동 downgrade가 shard count를 줄이면 hot/cold flapping 때 topology가 흔들릴 수 있다. 자동 정책은 shard count를 늘리기만 한다.
> - Prometheus 없이 release gate를 통과시키면 Phase 7에서 생긴 "형식 검증" 문제가 반복된다. 8.4 gate는 관측 파이프라인을 필수로 둔다.
> - 단일 로컬 Node 부하 발생기가 10k viewers와 10k msg/sec를 안정적으로 생성하지 못할 수 있다. 그 경우 gate 실패 원인은 시스템 한계와 발생기 한계를 구분해 기록해야 한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| `roomSeq` round-robin fanout shard | 균등 분산이 명확하고 순차 메시지가 shard 전체에 퍼진다 | raw arrival order가 흔들릴 수 있다 | 선택 |
| `messageId` hash fanout shard | 구현이 단순하고 write shard 계산과 같은 함수를 쓸 수 있다 | 분산 근거가 messageId 생성 품질에 의존하고 테스트 기대값이 덜 직관적이다 | 제외 |
| fanout shard는 유지하고 write shard만 확장 | raw ordering 영향이 작다 | B-4의 단일 fanout owner 병목을 해결하지 못한다 | 제외 |
| 자동 정책에서 shard count도 downgrade | 리소스 사용량을 낮출 수 있다 | flapping 때 stream topology가 자주 바뀌고 hot room 재상승 시 병목이 반복된다 | 제외 |
| 분산 부하 발생기까지 이번 슬라이스에 포함 | 10k gate 신뢰도가 가장 높다 | 별도 실행 인프라와 조율이 필요해 8.4 코드/계획 범위를 크게 키운다 | 후속 |

## 8. 후속 질문

- 10k gate가 단일 로컬 발생기 한계로 실패할 때, 분산 부하 발생기를 Phase 8 후속 hardening으로 둘 것인가?
- admin 수동 정책 override에 shard count 직접 조정 필드를 추가할 것인가?
- `VERY_HOT`에서 이미 `64` shard로 확장된 방의 장기 운영 비용을 줄이기 위한 수동 shrink 절차를 별도 runbook으로 둘 것인가?
