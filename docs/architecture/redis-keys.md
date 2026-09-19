# Redis 키와 Cluster 경계

아래는 현재 기본 prefix다. 실제 배포 값은 [ChatRedisProperties](../../chat-persistence/src/main/kotlin/config/ChatRedisProperties.kt)와 [ChatAuthProperties](../../chat-persistence/src/main/kotlin/config/ChatAuthProperties.kt)를 따른다. `<...>`는 문서 placeholder이며 `{...}`는 실제 Redis hash tag다.

| 용도 | 기본 패턴 | 생명 주기/경계 |
| --- | --- | --- |
| Sequence | `chat:sequence:<roomId>` | 메시지별 INCR 1; append와 별개 |
| 서버의 room index | `chat:server:rooms:<serverId>` | 로컬 구독 변화에 맞춰 Set 동기화, 실패 시 재시도 |
| Room Pub/Sub | `chat.room.<roomId>` | 일반 Pub/Sub channel, 비내구성 |
| Membership Pub/Sub | `chat.membership` | 멤버십 변경 알림 |
| Stream | `chat:stream:room:{<roomId>}:shard:<shardNo>` | ACK-safe prefix 회수 |
| Acceptance | `chat:stream:room:{<roomId>}:accepted:<senderId>:<encodedClientKey>` | 미처리 무기한, writer 저장/DLQ 이후 TTL |
| Known streams | `chat:stream:rooms` | stream 탐색 index |
| DLQ prefix | `chat:stream:dlq:` | group별 실패 record, 운영 대조 필요 |
| Admission | `chat:admission:room:{<roomId>}:rate:room:<epochSecond>` | room counter, 짧은 TTL |
| Admission | `chat:admission:room:{<roomId>}:rate:user:<userId>:<epochSecond>` | user counter, 짧은 TTL |
| Slow mode | `chat:admission:room:{<roomId>}:slow:user:<userId>` | slow-mode TTL |
| Traffic | `chat:room-traffic:{<roomId>}:sec:<epochSecond>` | accepted traffic window |
| Active traffic rooms | `chat:room-traffic:active-rooms` | score cleanup |
| Fanout lease | `chat:fanout:owner:room:<roomId>:shard:<streamShard>` | token 비교, 기본 10초 TTL |
| Ticket | `chat:ws-ticket:<sha256(ticket)>` | 단기 GETDEL |
| Ticket limit | `chat:ws-ticket:rate:user:<userId>` / `chat:ws-ticket:rate:ip:<sha256(ip)>` | 각각 단일 키 Lua |
| Session revocation | `chat:auth:session:revoked:token:<digest>` / `chat:auth:session:revoked:user:<userId>` | 만료 시각과 사용자 cutoff 기준 TTL |

## 원자성과 분산

Acceptance+stream append, room/user/slow-mode admission, room traffic multi-key 조회에는 동일 slot이 필요하다. 서로 다른 방은 room/slot 단위로 읽는다. Hash tag 도입 전 legacy stream은 key별로 분리해 cross-slot `XREADGROUP`를 피한다.

같은 방의 stream shard를 늘려도 Redis master에 걸쳐 분산되지 않는다. 단일 hot room의 slot 병목은 별도로 측정한다. 모든 도메인을 하나의 공통 hash tag로 묶지 않는다.

단일 키 GETDEL, lease Lua, ticket user/IP 각각의 제한에는 hash tag가 필요하지 않다. Ticket 두 제한은 결합 원자성이 없으며 [판단 기준](authentication.md#발급-제한)에 따라 관리한다.

Lease token 확인은 publish와 원자적이지 않다. Sequence도 append와 원자적이지 않다. 구체적인 전달·순서 한계는 [메시지 계약](messaging.md)을 따른다.
