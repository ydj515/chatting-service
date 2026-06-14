# Phase 2 Message Contract Sequence And Idempotency Retrospective Plan

> **For agentic workers:** This is a retrospective completion record for an already implemented phase. Use it to understand the delivered scope, verification path, and remaining risks before changing message contract behavior.

**Goal:** Stabilize the accepted message contract so later Redis Streams, batch fan-out, and PostgreSQL canonical storage phases can reuse the same identifiers and ordering semantics.

**Architecture:** Each accepted message receives a server `messageId`, caller-provided `clientMessageId`, room-scoped `roomSeq`, and independent shard fields. Redis `INCRBY` sequence blocks provide monotonic room ordering with allowed gaps, while database uniqueness and duplicate handling provide idempotency for client retries.

**Tech Stack:** Kotlin, Spring Boot, Spring Data Redis, JPA, PostgreSQL, React client, JUnit 5, Node verify script.

---

## Status

- Complete.
- This phase is already reflected in the current codebase.
- The source-of-truth roadmap remains `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`.

## Preconditions

- Phase 1 authenticated WebSocket identity is complete.
- Gateway fan-out uses local room indexes.
- API and WebSocket paths resolve the same authenticated user identity.

## Delivered Slices

### Slice 2.1 Message Envelope Fields

1. Accepted messages include a server-issued `messageId`.
2. Accepted messages include a caller-provided `clientMessageId` for retry deduplication.
3. Accepted messages include a room-scoped `roomSeq`.
4. Accepted messages include independent `streamShard`, `writeShard`, and `fanoutShard` fields.
5. Existing compatibility fields remain available for old history paths.

### Slice 2.2 Redis Sequence Block Allocation

1. `MessageSequenceService` allocates room sequence blocks through Redis `INCRBY`.
2. Each application instance can consume an allocated block locally.
3. Sequence gaps are explicitly allowed when an instance dies after block allocation.
4. Display ordering uses `roomSeq` as the deterministic room order key.

### Slice 2.3 Client Message Idempotency

1. `(roomId, senderId, clientMessageId)` identifies a logical client retry.
2. Re-sending the same `clientMessageId` returns the previously accepted message result.
3. Concurrent duplicate inserts are handled by re-querying the existing message after a unique constraint conflict.
4. Idempotency preserves the original `messageId` and `roomSeq`.

### Slice 2.4 WebSocket Event Contract

1. `MESSAGE_ACCEPTED` confirms that the server accepted the message into the current phase's acceptance path.
2. `CHAT_MESSAGE` remains the single-message broadcast event.
3. `CHAT_MESSAGE_BATCH` defines the future batch fan-out frame contract.
4. The client parses both single-message and batch frames.

### Slice 2.5 Client Ordering And Deduplication

1. The client deduplicates messages by `messageId`.
2. The client sorts displayed room messages by `roomSeq`.
3. The client keeps ACK handling separate from broadcast handling.
4. The client remains compatible with later batch fan-out.

## Key Files

- `chat-domain/src/main/kotlin/model/Message.kt`
- `chat-domain/src/main/kotlin/dto/WebSocketDto.kt`
- `chat-domain/src/main/kotlin/dto/ChatDto.kt`
- `chat-persistence/src/main/kotlin/service/MessageSequenceService.kt`
- `chat-persistence/src/main/kotlin/config/MessageSequenceProperties.kt`
- `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`
- `chat-persistence/src/main/kotlin/repository/MessageRepository.kt`
- `chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt`
- `client/src/hooks/useWebSocket.ts`
- `client/src/components/ChatWindow.tsx`
- `scripts/verify-chat.mjs`

## Completion Criteria

- New messages have `messageId`, `clientMessageId`, `roomSeq`, `streamShard`, `writeShard`, and `fanoutShard`.
- Re-sending the same `clientMessageId` does not create duplicate logical messages.
- Concurrent duplicate sends return the existing message contract after the unique constraint resolves the race.
- A lost sequence block can create gaps, but room ordering remains monotonic by `roomSeq`.
- The client handles ACK and broadcast events separately.
- The client can parse both `CHAT_MESSAGE` and `CHAT_MESSAGE_BATCH`.
- Existing `mise run verify:chat` flow passes with the new contract.

## Verification

Run:

```bash
./gradlew test --no-daemon
./gradlew test --tests '*MessageSequenceServiceTest' --no-daemon
cd client && npm test -- --run
mise run verify:chat
```

Expected:

- Gradle tests pass.
- Sequence allocation tests pass.
- Client tests pass.
- `verify-chat` proves the message contract works end to end.

## Operational Complexity

- Sequence allocation time complexity: `O(1)` per block allocation and `O(1)` per message while consuming a local block.
- Idempotency lookup time complexity: `O(log N_room_sender_client)` through the unique/indexed key, assuming PostgreSQL index access.
- Client deduplication time complexity: `O(1)` average per message with a `messageId` set/map.
- Client display ordering time complexity: `O(M log M)` when sorting a room message list of size `M`; incremental insertion can reduce this to `O(log M)` lookup plus insertion cost depending on the data structure.
- Server-side additional space complexity: `O(B_active_rooms)` for local sequence blocks and `O(N_messages)` for persisted message identifiers.

## Caveats

> - `roomSeq` is monotonic but not gapless. Gapless ordering would reduce throughput and make hot rooms more fragile during instance failure.
> - `MESSAGE_ACCEPTED` is not the same as "delivered to every viewer". Later phases move acceptance to Redis Streams append and use history/gap fill to repair delivery misses.
> - `streamShard`, `writeShard`, and `fanoutShard` must remain conceptually independent. Coupling them makes later scaling and rebalancing harder.

## Alternatives Considered

| Alternative | Pros | Cons |
| --- | --- | --- |
| PostgreSQL sequence per room | Strong database-backed ordering | Hot rooms concentrate on database writes and sequence contention |
| Redis `INCR` per message | Simple monotonic sequence | One Redis round trip per message; weaker throughput under hot rooms |
| Redis `INCRBY` block allocation | Fewer Redis calls and good horizontal scaling | Allows gaps after instance failure |
| Gapless strict ordering | Easier mental model | Lower availability and throughput for hot rooms |

