# Phase 2.5 WebSocket Ticket Hardening Retrospective Plan

> **For agentic workers:** This is a retrospective completion record for an already implemented phase. Use it to understand the delivered scope, verification path, and remaining risks before changing WebSocket ticket behavior.

**Goal:** Remove reusable session credentials from WebSocket URLs in production-like environments by introducing short-lived one-time WebSocket tickets.

**Architecture:** Authenticated REST clients call `POST /api/ws-tickets` with a bearer session token, receive a short-lived random ticket, and use that ticket once in the WebSocket handshake. Redis stores only a SHA-256 ticket hash with a TTL-bound user context, and the handshake consumes the ticket atomically.

**Tech Stack:** Kotlin, Spring Boot, Spring WebSocket, Spring Data Redis, Lua-backed Redis rate limit script, React client, Node verify script, Micrometer.

---

## Status

- Complete.
- This phase is already reflected in the current codebase.
- Related hardening details are tracked in `docs/ws_ticket_analysis.md`, `docs/production_hardening_tasks.md`, `docs/redis_cluster_key_naming.md`, and `docs/observability_metrics.md`.

## Preconditions

- Phase 2 message contract is complete.
- REST clients can authenticate with a session token in the `Authorization` header.
- WebSocket handshake can resolve authenticated identity from server-side validation.

## Delivered Slices

### Slice 2.5.1 Ticket Service Contract

1. `WebSocketTicketService` issues one-time tickets for authenticated users.
2. Ticket responses include the ticket value and expiration metadata.
3. Ticket values are generated as high-entropy random values.
4. Redis stores `sha256(ticket)` rather than the plaintext ticket.

### Slice 2.5.2 Ticket Issue API

1. `POST /api/ws-tickets` issues a WebSocket ticket.
2. The endpoint requires `Authorization: Bearer {sessionToken}`.
3. The endpoint returns `429` when ticket issue rate limits reject the request.
4. API reference and OpenAPI docs include the ticket endpoint.

### Slice 2.5.3 Atomic Ticket Consume

1. WebSocket handshake reads the `ticket` query parameter.
2. The handshake consumes the ticket atomically from Redis.
3. A consumed ticket cannot be reused.
4. Expired or unknown tickets fail the handshake.

### Slice 2.5.4 Production Fallback Policy

1. Local development can keep session-token query fallback when explicitly enabled.
2. Docker and production-like configuration disable reusable session-token query fallback.
3. `userId` query parameter remains non-authoritative.
4. The client obtains a fresh ticket immediately before connect or reconnect.

### Slice 2.5.5 Ticket Rate Limit And Metrics Baseline

1. Ticket issue rate limits apply per user and per IP.
2. Redis Lua script execution makes each individual counter update atomic with TTL handling.
3. Lua script failure uses fail-closed behavior for ticket issue.
4. Ticket issue latency and Redis script failure metrics are available for the Phase 7 release gate.
5. Full user/IP combined atomicity remains an operational decision documented in the production hardening and Phase 7 reconnect test documents.

## Key Files

- `chat-api/src/main/kotlin/controller/WebSocketTicketController.kt`
- `chat-api/src/test/kotlin/controller/WebSocketTicketControllerTest.kt`
- `chat-domain/src/main/kotlin/service/SessionTokenService.kt`
- `chat-domain/src/main/kotlin/dto/UserDto.kt`
- `chat-persistence/src/main/kotlin/service/HmacSessionTokenService.kt`
- `chat-persistence/src/main/kotlin/service/RedisWebSocketTicketService.kt`
- `chat-persistence/src/test/kotlin/service/RedisWebSocketTicketServiceTest.kt`
- `chat-websocket/src/main/kotlin/interceptor/WebSocketHandshakeInterceptor.kt`
- `chat-websocket/src/test/kotlin/interceptor/WebSocketHandshakeInterceptorTest.kt`
- `chat-runtime-config/src/main/resources/application-docker.yml`
- `client/src/services/api.ts`
- `client/src/hooks/useWebSocket.ts`
- `scripts/verify-chat.mjs`
- `docs/api-reference.md`
- `docs/openapi.yaml`
- `docs/configuration.md`
- `docs/ws_ticket_analysis.md`
- `docs/production_hardening_tasks.md`
- `docs/redis_cluster_key_naming.md`
- `docs/observability_metrics.md`
- `docs/phase7_reconnect_load_test_scenarios.md`

## Completion Criteria

- Authenticated users can issue WebSocket one-time tickets through `POST /api/ws-tickets`.
- Redis stores only hashed ticket keys with a short TTL-bound user context.
- WebSocket handshake accepts a valid unused ticket.
- WebSocket handshake rejects a reused ticket.
- Docker and production-like configuration reject reusable session-token query fallback.
- Client reconnect obtains a new ticket before opening a new WebSocket.
- Ticket issue rate limit paths fail closed on Redis script failure.
- Ticket issue latency and script failure metrics are documented for dashboard wiring.

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
- `verify-chat` proves ticket issue, ticket consume, ticket reuse rejection, and normal chat flow.
- Production-like runtime does not accept reusable session-token query fallback.

## Operational Complexity

- Ticket issue time complexity: `O(1)` Redis operations for user and IP counters plus ticket storage.
- Ticket consume time complexity: `O(1)` Redis atomic consume by hashed ticket key.
- Ticket storage space complexity: `O(T_active)`, where `T_active` is the number of unexpired issued tickets.
- Rate limit counter space complexity: `O(U_window + I_window)`, where `U_window` is active users in the rate limit window and `I_window` is active IP buckets in the rate limit window.

## Caveats

> - One-time tickets reduce credential exposure in URLs, but they still appear in the WebSocket URL during the short TTL window. Access logs must avoid recording query strings.
> - User/IP rate limits are individually atomic. Full combined atomicity across user and IP counters is intentionally gated by Phase 7 reconnect failure measurements.
> - Fail-closed ticket issue protects abuse boundaries but can hurt reconnect UX during Redis incidents. Phase 7 synthetic reconnect tests must measure that tradeoff.
> - Redis Cluster key naming must preserve hash slot compatibility for each Lua script's keys.

## Alternatives Considered

| Alternative | Pros | Cons |
| --- | --- | --- |
| Keep reusable session token in WebSocket query | Simple client and server flow | Higher credential exposure through URLs and logs |
| Use WebSocket subprotocol/header-only auth | Avoids query token exposure | Browser WebSocket APIs limit custom headers; operational compatibility is weaker |
| Use one-time ticket | Short credential lifetime and one-time consume semantics | Requires extra REST round trip before connect/reconnect |
| Require full user/IP combined atomicity immediately | Strongest accounting semantics | More Redis Cluster hash-slot constraints and higher chance of concentrating hot keys |
