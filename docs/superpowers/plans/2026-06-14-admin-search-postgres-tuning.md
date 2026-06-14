# Admin Search PostgreSQL Tuning Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:test-driven-development` for implementation changes. Keep commits sliced by behavior: docs, API/search-mode behavior, PostgreSQL DDL, measurement docs.

**Goal:** Keep Phase 5 admin search PostgreSQL-first, reduce cold-query and p99 risk for hot-room search, and avoid adding OpenSearch before PostgreSQL tuning is exhausted.

**Target:** 10M local validation keeps warm p95 `<= 1000ms`, while cold `EXPLAIN (ANALYZE, BUFFERS)` for room-scoped keyword search improves materially from the Phase 5 baseline.

## Conditions

- PostgreSQL remains the canonical search backend for this task.
- OpenSearch is not introduced in this task.
- Admin search must remain compatible with role-separated `chat-admin` and read-replica routing.
- Existing 10M seed data is reused for measurement.
- Search UI and measurement scripts must expose any search-mode behavior that operators can choose.
- DDL must support partitioned `chat_messages` tables and hot-room hash subpartitions.

## Current Findings

- Existing admin search combines FTS and trigram search with:

```sql
cm.content_tsv @@ plainto_tsquery('simple', ?) OR cm.content ILIKE ?
```

- That `OR` path makes PostgreSQL planner choices less predictable under cold cache.
- Existing DDL has standalone `GIN(content_tsv)` and `GIN(content gin_trgm_ops)` indexes.
- Hot-room search benefits from room-scoped text index access, but DDL does not yet create a room-aware FTS index.
- Manual experiment on 10M local data showed room-aware `GIN(room_id int8_ops, content_tsv)` improves the cold FTS-only path materially.

## Work Items

- [ ] Document tuning task and baseline findings.
- [ ] Add RED tests proving default admin search uses FTS-only SQL.
- [ ] Add explicit `CONTAINS` search mode for substring/trigram fallback.
- [ ] Pass search mode through `chat-admin`, `chat-domain`, `chat-persistence`, audit metadata, and `client-admin`.
- [ ] Add PostgreSQL `btree_gin` extension and room-aware FTS indexes to default and daily hash partitions.
- [ ] Add measurement CLI support for search mode.
- [ ] Update API and performance docs.
- [ ] Run targeted tests.
- [ ] Rebuild/restart local stack and rerun 10M admin search measurement.
- [ ] Commit each slice separately.

## API Behavior

- Default `GET /admin/messages/search` mode: `FTS`.
- Optional `mode=CONTAINS` enables substring search backed by trigram indexes.
- `FTS` is the production default because it is faster and more predictable for operator keyword search.
- `CONTAINS` is retained for exact substring investigations, but operators should expect higher tail latency.

## PostgreSQL DDL

- Enable `btree_gin` so `room_id` can participate in a composite GIN index.
- Add:

```sql
CREATE INDEX IF NOT EXISTS ix_chat_messages_default_room_content_tsv
ON chat_messages_default USING gin (room_id int8_ops, content_tsv);
```

- Add equivalent indexes inside `create_chat_messages_daily_partition(...)` for every hash child partition.
- Do not add room-aware trigram indexes by default in this task. They increase write amplification and are only useful for explicit `CONTAINS` fallback.

## Verification Commands

```bash
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminMessageRepositoryTest --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon
npm --prefix client-admin test
node --test scripts/lib/adminMeasurePlan.test.mjs
```

After implementation and local stack restart:

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --from 2026-06-13T11:46:50 \
  --to 2026-06-14T15:33:25 \
  --search-mode FTS \
  --requests 100 \
  --warmup 10 \
  --concurrency 5 \
  --target-p95-ms 1000 \
  --output docs/performance/admin_search_postgres_tuning_2026-06-14.json
```

## Complexity

- FTS search with room filter: approximately `O(log N_text_room + L)` when PostgreSQL uses the room-aware GIN index, where `L` is page limit.
- `CONTAINS` substring search: approximately `O(log N_trgm + K + L)` with higher constant cost and more candidate rechecks, where `K` is trigram candidate count.
- History search remains approximately `O(log N_room + L)`.
- Additional index storage: `O(N)` for the room-aware FTS index across all message rows.

## Caveats

> - Composite GIN indexes improve read tail latency but add write and storage cost.
> - Local Docker Compose p95 is a regression signal, not a production SLO proof.
> - Partition fan-out and cold cache can still produce p99 outliers; Phase 7 observability must continue tracking p95/p99 and slow query plans.
> - `CONTAINS` is intentionally explicit because substring search quality costs more than FTS.

## Alternatives

| Option | Pros | Cons | Decision |
| --- | --- | --- | --- |
| PostgreSQL FTS-only default + explicit CONTAINS fallback | Lowest operational complexity, improves current hot-room search path, keeps one datastore | Search UX is less flexible than OpenSearch; cold p99 still needs tuning | Use now |
| Add room-aware trigram composite indexes too | Better explicit substring search tail latency | Higher write amplification and larger index set | Defer until CONTAINS p95 data requires it |
| Introduce OpenSearch | Better search ranking, analyzers, and tail-latency headroom | New cluster, indexing pipeline, consistency lag, operational burden | Defer until PostgreSQL tuning fails SLO |
