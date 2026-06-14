# Phase 1 Auth And Gateway Local Fan-Out Retrospective Plan

> **For agentic workers:** This is a retrospective completion record for an already implemented phase. Use it to understand the delivered scope, verification path, and remaining risks before changing Phase 1 behavior.

**Goal:** Make WebSocket connections use an authenticated user principal and make Gateway fan-out scale with local room sessions instead of all local user sessions.

**Architecture:** REST login issues a session token, REST and WebSocket paths resolve the same authenticated user, and the Gateway keeps local room/session/user indexes for fan-out. Slow WebSocket clients are isolated by a bounded outbound queue so one connection cannot consume unbounded memory.

**Tech Stack:** Kotlin, Spring Boot, Spring WebSocket, Redis presence, React client, Node verify script.

---

## Status

- Complete.
- This phase is already reflected in the current codebase.
- The source-of-truth roadmap remains `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`.

## Preconditions

- Phase 0 local PostgreSQL primary/replica/archive setup is complete.
- Phase 0.5 role-based application modules are complete.
- API, WebSocket, Worker, and Admin application modules can be started independently.

## Delivered Slices

### Slice 1.1 Session Token Authentication Foundation

1. Login responses include a session token.
2. REST requests prefer the authenticated token principal over caller-controlled identifiers.
3. WebSocket handshake extracts an authentication token and stores the verified `userId` in the WebSocket session attributes.
4. Query parameter `userId` is no longer trusted as the authentication principal.

### Slice 1.2 Gateway Local Session Indexes

1. `WebSocketSessionManager` maintains `roomId -> sessions` for room-local delivery.
2. `WebSocketSessionManager` maintains `sessionId -> rooms` for cleanup on disconnect.
3. `WebSocketSessionManager` maintains `userId -> sessions` for user-level connection tracking.
4. Room join, room leave, and connection close update local indexes and Redis presence consistently.

### Slice 1.3 Room-Scoped Local Fan-Out

1. Local broadcast sends only to sessions currently indexed in the target room.
2. Message fan-out avoids scanning every local user session.
3. Room membership is validated at connection/join time instead of querying the database for every fan-out recipient.
4. Fan-out complexity changes from `O(U_local)` to `O(S_local_room)`.

### Slice 1.4 Bounded Outbound Queue

1. Each WebSocket session has a bounded outbound queue.
2. Slow clients cannot grow Gateway memory without limit.
3. Queue overflow follows the configured policy instead of blocking all room delivery.
4. Session send synchronization protects against concurrent WebSocket writes.

## Key Files

- `chat-api/src/main/kotlin/controller/UserController.kt`
- `chat-domain/src/main/kotlin/dto/UserDto.kt`
- `chat-domain/src/main/kotlin/service/UserService.kt`
- `chat-domain/src/main/kotlin/service/SessionTokenService.kt`
- `chat-persistence/src/main/kotlin/service/UserServiceImpl.kt`
- `chat-websocket/src/main/kotlin/interceptor/WebSocketHandshakeInterceptor.kt`
- `chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt`
- `chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt`
- `client/src/services/api.ts`
- `client/src/hooks/useWebSocket.ts`
- `scripts/verify-chat.mjs`

## Completion Criteria

- WebSocket can connect without `ws://.../ws/chat?userId=...` being trusted as identity.
- A modified `userId` query parameter cannot impersonate another user.
- Message fan-out only iterates sessions in the target room on the current Gateway instance.
- Room membership checks are not performed once per recipient during broadcast.
- Slow client queue usage remains bounded.
- Existing one-to-one message send, receive, and history flows continue to work.

## Verification

Run:

```bash
./gradlew test --no-daemon
mise run start:all
mise run verify:chat
curl -fsS http://localhost/api/actuator/health
```

Expected:

- Gradle tests pass.
- All role applications start through the local compose/mise flow.
- `verify-chat` passes without relying on caller-controlled WebSocket `userId`.
- API health returns success.

## Operational Complexity

- Fan-out time complexity: `O(S_local_room)`, where `S_local_room` is the number of sessions for the target room on the current Gateway.
- Disconnect cleanup time complexity: `O(R_session)`, where `R_session` is the number of rooms joined by the disconnecting session.
- Session index space complexity: `O(S_local + M_local)`, where `S_local` is local WebSocket sessions and `M_local` is local room membership references.

## Caveats

> - Phase 1 only improves local Gateway fan-out. Cross-Gateway fan-out still depends on the later Redis Streams and Fanout Worker phases.
> - Session tokens are still reusable credentials. WebSocket one-time ticket hardening is intentionally handled in Phase 2.5.
> - Bounded queues protect Gateway memory but do not guarantee every live message reaches slow clients. Gap fill and history repair remain part of the broader design.

## Alternatives Considered

| Alternative | Pros | Cons |
| --- | --- | --- |
| Keep scanning all local users | Simple implementation | Does not scale for large rooms or many local sessions |
| Use only Redis Pub/Sub membership without local indexes | Less local state | More remote lookups and weaker disconnect cleanup ergonomics |
| Maintain local room/session/user indexes | Room fan-out scales with actual local room sessions and cleanup is deterministic | Requires careful join/leave/disconnect bookkeeping |

