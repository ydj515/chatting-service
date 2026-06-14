# Admin Search Opaque Cursor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace admin message search pagination with an opaque compound cursor so global search cannot skip or duplicate rows when `roomSeq` differs from global time ordering.

**Architecture:** Room history keeps its existing `Long roomSeq` cursor. Admin search uses a Base64URL opaque cursor that encodes `createdAt`, `roomSeq`, and `messageId`; repository SQL applies the same tuple in both `WHERE` and `ORDER BY`.

**Tech Stack:** Kotlin/Spring Boot, PostgreSQL JDBC, Base64URL cursor encoding, Node test runner for client-admin and script/docs contract tests.

---

### Task 1: Domain Cursor Contract

**Files:**
- Modify: `chat-domain/src/main/kotlin/dto/AdminDto.kt`
- Create: `chat-domain/src/main/kotlin/dto/AdminMessageSearchCursor.kt`
- Test via persistence/service/controller tests.

- [x] Change `AdminMessageSearchRequest.cursor` from `Long?` to `String?`.
- [x] Change `AdminMessageSearchResponse.nextCursor` from `Long?` to `String?`.
- [x] Add `AdminMessageSearchCursor(createdAt: Instant, roomSeq: Long, messageId: String)`.
- [x] Add `AdminMessageSearchCursorCodec.encode/decode` using Base64URL without padding.
- [x] Invalid cursor strings throw `IllegalArgumentException("Invalid admin search cursor")`.

### Task 2: Repository Tuple Pagination

**Files:**
- Modify: `chat-persistence/src/main/kotlin/repository/AdminMessageRepository.kt`
- Modify: `chat-persistence/src/test/kotlin/repository/AdminMessageRepositoryTest.kt`

- [x] Add RED test proving search cursor predicate uses `created_at`, `room_seq`, and `message_id`.
- [x] Update `searchMessages` to accept `AdminMessageSearchCursor?`.
- [x] Add predicate:
  `(cm.created_at < ?) OR (cm.created_at = ? AND cm.room_seq < ?) OR (cm.created_at = ? AND cm.room_seq = ? AND cm.message_id < ?)`.
- [x] Update order to `ORDER BY cm.created_at DESC, cm.room_seq DESC, cm.message_id DESC`.

### Task 3: Service Cursor Encode/Decode

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/AdminChatServiceImpl.kt`
- Modify: `chat-persistence/src/test/kotlin/service/AdminChatServiceImplTest.kt`

- [x] Decode request cursor before repository call.
- [x] Encode `nextCursor` from the last visible search message when `hasNext=true`.
- [x] Keep room history `nextCursor` unchanged as `Long`.

### Task 4: Controller And Client Contract

**Files:**
- Modify: `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminChatController.kt`
- Modify: `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminChatControllerTest.kt`
- Modify: `client-admin/src/services/adminApi.test.mjs`

- [x] Change admin search `cursor` query parameter to `String?`.
- [x] Validate malformed admin search cursor as `400 Bad Request`.
- [x] Ensure client-admin sends opaque string cursor without numeric coercion.

### Task 5: Docs And DDL

**Files:**
- Modify: `infra/postgres/message-partitions.sql`
- Modify: `scripts/lib/postgresDdl.test.mjs`
- Modify: `docs/openapi.yaml`
- Modify: `docs/api-reference.md`

- [x] Add cursor-friendly ordering indexes for default and daily hash partitions.
- [x] Update OpenAPI admin search cursor parameter and `nextCursor` schema to string.
- [x] Document admin search opaque cursor format as opaque to clients, not decoded by UI.

### Task 6: Verification

- [x] Run `./gradlew test --no-daemon`.
- [x] Run `./gradlew check --no-daemon`.
- [x] Run `npm --prefix client-admin test`.
- [x] Run `npm --prefix client-admin run build`.
- [x] Run `node --test scripts/lib/*.test.mjs`.
- [x] Run `git diff --check`.
