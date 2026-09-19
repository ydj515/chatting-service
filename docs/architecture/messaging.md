# 메시지 수락과 전달

## 수락과 재시도

1. 방·사용자·멤버십을 확인하고 primary DB와 Redis에서 `(roomId, senderId, clientMessageId)`의 기존 메시지/수락 결과를 조회한다.
2. 신규 메시지만 모더레이션 및 room/user rate limit·slow mode를 검사한다. Admission의 세 Redis 키는 같은 room hash tag에 두고 Lua로 함께 판정·차감한다.
3. 메시지별 Redis `INCR 1`로 `roomSeq`를 발급한다. Gateway별 sequence block 선할당은 사용하지 않는다.
4. Acceptance Lua가 중복 여부를 다시 확인하고 원본 envelope의 stream append와 수락 결과 저장을 원자적으로 처리한다.
5. `MESSAGE_ACCEPTED`를 반환한다. DB 저장과 fanout은 각 worker가 비동기로 수행한다.

관련 구현: [accept-message.lua](../../chat-persistence/src/main/resources/redis/accept-message.lua). Sequence 발급은 이 Lua와 별개다. 발급 이후 실패하면 hole이 생길 수 있고, 동시 요청의 append 순서가 sequence 발급 순서와 다를 수 있다.

미처리 acceptance payload에는 TTL을 두지 않는다. Writer가 DB 저장 또는 DLQ 기록에 성공한 뒤 24시간 TTL을 시작한다. DLQ로 이동한 메시지가 DB에 없고 acceptance TTL까지 만료되면 같은 clientMessageId 재요청이 신규 수락될 수 있다. 운영 DLQ replay는 원래 messageId·roomSeq·clientMessageId를 유지해야 한다.

## Stream 용량과 처리 상태

`chat.redis.streams.max-len` 기본값 1,000,000은 신규 수락 용량 제한이다. unread/pending entry를 버리는 `MAXLEN` trim으로 동작하지 않는다.

- 용량에 도달하면 writer와 fanout 필수 group이 모두 존재하는지 확인한다.
- 모든 기존 consumer group이 ACK한 prefix만 회수한다. 필요한 공간을 확보할 수 없으면 신규 수락을 거부한다.
- HTTP는 429, WebSocket은 `MESSAGE_ADMISSION_REJECTED`로 거부를 알린다.
- `maxLen <= 0`이면 무제한이다. `maxLenApproximate`는 호환 설정으로 남아 있지만 현재 회수에서는 사용하지 않는다.
- Gateway와 Worker의 consumer group 이름이 일치해야 한다. 과거 destructive trim을 수행하는 Gateway가 혼재하지 않도록 [전환 절차](../operations/migrations.md)를 따른다.

## Writer와 Fanout

Writer는 batch 저장, DB 멱등성, pending claim, 최대 전달 횟수 이후 DLQ 이동을 수행한다. Fanout은 room/stream shard 단위 TTL lease를 얻어 batch publish하고 ACK한다. 기본 lease TTL은 10초, 갱신 간격은 3초다.

Lease의 임의 token을 비교해 갱신·해제하고 publish/ACK 직전에도 소유권을 확인한다. 소유권 확인과 publish는 하나의 원자 연산이 아니므로 pause/장애 경계의 중복까지 막는 fencing 또는 exactly-once 보장으로 해석하지 않는다. Publish 후 ACK 전에 실패하면 재전달될 수 있다.

Malformed·누락·null payload는 raw fields와 원본 stream/record 식별자를 consumer group별 DLQ에 기록한 뒤 ACK한다. DLQ 기록 실패 시 해당 entry는 pending에 남고 유효한 이웃 entry 처리는 계속한다. DLQ 성공 후 ACK 실패로 DLQ가 중복될 수 있으므로 `(sourceStreamKey, sourceRecordId, consumerGroup)`으로 대조한다.

## Shard와 클라이언트

- Stream shard는 `floorMod(roomSeq - 1, fanoutShardCount)`, write shard는 messageId 기반으로 선택하고 fanout shard는 stream shard를 따른다.
- 같은 방의 stream shard는 같은 Redis hash slot에 있다. Worker 병렬 처리 단위를 늘리는 설정이며, 한 hot room을 Redis master 여러 개로 분산하는 설정은 아니다.
- Gateway는 Redis room channel을 구독하고 로컬 세션에 전달한다. 전달 batch마다 로컬 수신 사용자들의 활성 멤버십을 primary DB에서 한 번에 조회한다. 탈퇴한 사용자는 제외·정리하며 DB 확인 실패 시 enqueue를 진행하지 않는다.
- 멤버십 이벤트를 놓친 Gateway도 다음 전달에서 탈퇴자를 걸러낸다. 대신 전달 batch마다 DB 조회 비용이 추가된다. JOIN 이벤트 전달은 여전히 best-effort다.
- 클라이언트는 messageId 중복 제거, roomSeq 정렬, history gap fill, reconnect 시 방 목록 전체 페이지 복구를 수행한다. 기본 live feed 보관 범위는 1,000개/60초다.

## 관측과 복구

Lag와 pending은 서로 다른 신호다. [Streams 지표](../operations/streams-lag-metrics.md), [경보 대응](../operations/streams-lag-alerts.md), [takeover 검증](../testing/fanout-takeover.md)을 함께 사용한다.

RoomSeq gap audit는 canonical store의 aggregate gap을 보여 준다. 수락 전 sequence 발급 후 실패한 hole도 포함할 수 있어, gap 수만으로 실제 메시지 유실을 확정하지 않는다. Acceptance, DB, pending, DLQ를 대조해야 한다. Redis AOF `everysec` 장애 창과 Pub/Sub의 비내구성은 별도 한계다.
