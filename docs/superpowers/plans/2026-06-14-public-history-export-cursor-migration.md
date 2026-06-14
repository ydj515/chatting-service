# Public History And Export Cursor Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backward-compatible opaque cursors for public room history and make admin message export read in resumable chunks.

**Architecture:** Public room history keeps existing numeric `cursor`, `nextCursor`, and `prevCursor` fields while adding opaque `cursorToken`, `nextCursorToken`, and `prevCursorToken`. The service treats `cursorToken` as authoritative when both token and numeric cursors are supplied, then falls back to numeric `cursor` for legacy clients. Admin export reads message pages in bounded chunks and records the last exported cursor token so a requeued export job can resume from the previous checkpoint.

**Tech Stack:** Kotlin/Spring Boot, PostgreSQL JDBC, Jackson DTOs, Base64URL cursor encoding, Node OpenAPI contract tests.

---

## Cursor Rules

- Public room history ordering remains `roomSeq DESC`.
- Public cursor token payload contains `createdAt`, `roomSeq`, and `messageId`.
- Public clients must treat cursor tokens as opaque and pass them back unchanged.
- If `cursorToken` and numeric `cursor` are both supplied, `cursorToken` wins.
- Numeric cursor compatibility is retained for `2 releases or 30 days after public client migration`, whichever is longer.
- Admin export chunk pagination uses the same cursor payload but keeps repository predicates aligned with each query's own `ORDER BY`.

## Slice 1: Public Room History Opaque Cursor Compatibility

**Files:**
- Modify: `chat-domain/src/main/kotlin/dto/ChatDto.kt`
- Modify: `chat-domain/src/main/kotlin/dto/AdminMessageSearchCursor.kt`
- Modify: `chat-api/src/main/kotlin/controller/ChatController.kt`
- Modify: `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`
- Modify: `chat-api/src/test/kotlin/controller/ChatControllerTest.kt`
- Modify: `chat-persistence/src/test/kotlin/service/ChatServiceImplCanonicalHistoryTest.kt`
- Modify: `chat-persistence/src/test/kotlin/service/ChatServiceImplCursorPaginationTest.kt`
- Modify: `client/src/types/index.ts`
- Modify: `client/src/services/api.ts`
- Modify: `docs/openapi.yaml`
- Modify: `docs/api-reference.md`
- Test: `./gradlew :chat-api:test --tests com.chat.api.controller.ChatControllerTest --no-daemon`
- Test: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.ChatServiceImplCanonicalHistoryTest --tests com.chat.persistence.service.ChatServiceImplCursorPaginationTest --no-daemon`

- [x] Add `cursorToken` to `MessagePageRequest`.
- [x] Add `nextCursorToken` and `prevCursorToken` to `MessagePageResponse`.
- [x] Reuse the generic admin message cursor codec through public aliases named `MessageHistoryCursor` and `MessageHistoryCursorCodec`.
- [x] Validate malformed public `cursorToken` as `400 Bad Request`.
- [x] Encode next/previous tokens from the same message boundaries as numeric cursors.
- [x] Update public client API to send `cursorToken` while retaining numeric cursor compatibility.

## Slice 2: Public Numeric Cursor Deprecation Policy

**Files:**
- Create: `docs/public_history_cursor_migration.md`
- Modify: `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`
- Modify: `docs/api-reference.md`
- Modify: `docs/openapi.yaml`
- Test: `node --test scripts/lib/openapiAdminContract.test.mjs scripts/lib/postgresDdl.test.mjs`

- [x] Document that numeric public history cursors are deprecated but still accepted.
- [x] Document the deprecation window: `2 releases or 30 days after public client migration`, whichever is longer.
- [x] Document precedence: `cursorToken` wins over numeric `cursor`.
- [x] Document rollback behavior: legacy clients can keep using numeric cursor until the deprecation window closes.

## Slice 3: Admin Export Worker Internal Pagination

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt`
- Modify: `chat-persistence/src/test/kotlin/service/AdminMessageExportWorkerTest.kt`
- Test: `./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminMessageExportWorkerTest --no-daemon`

- [x] Add configurable `exportChunkSize` with default `1000`.
- [x] Read room history exports in repeated chunk queries instead of one `EXPORT_MAX_ROWS` query.
- [x] Read keyword exports in repeated chunk queries using search cursor pagination.
- [x] Write CSV rows incrementally while preserving spreadsheet formula injection protection.
- [x] Keep total export cap at `10000` rows.

## Slice 4: Admin Export Resume Checkpoint

**Files:**
- Modify: `infra/postgres/message-partitions.sql`
- Modify: `chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt`
- Modify: `chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt`
- Modify: `chat-persistence/src/test/kotlin/repository/AdminExportJobRepositoryTest.kt`
- Modify: `chat-persistence/src/test/kotlin/service/AdminMessageExportWorkerTest.kt`
- Modify: `scripts/lib/postgresDdl.test.mjs`
- Modify: `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`
- Test: `./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --tests com.chat.persistence.service.AdminMessageExportWorkerTest --no-daemon`
- Test: `node --test scripts/lib/postgresDdl.test.mjs`

- [ ] Add `cursor_token` and `exported_rows` checkpoint columns.
- [ ] Return checkpoint fields when a pending export job is claimed.
- [ ] Update checkpoint after each successfully written chunk.
- [ ] Resume from checkpoint when a previously started job is requeued to `PENDING`.
- [ ] Preserve checkpoint fields when a job fails.
- [ ] Document resume semantics and the best-effort duplicate risk window between file write and checkpoint update.

## Final Verification

- [ ] Run `./gradlew test --no-daemon`.
- [ ] Run `./gradlew check --no-daemon`.
- [ ] Run `npm --prefix client run build`.
- [ ] Run `npm --prefix client-admin test`.
- [ ] Run `npm --prefix client-admin run build`.
- [ ] Run `node --test scripts/lib/*.test.mjs`.
- [ ] Run `git diff --check`.
