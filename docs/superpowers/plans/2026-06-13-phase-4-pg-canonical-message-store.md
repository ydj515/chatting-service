# Phase 4 PostgreSQL Canonical Message Store Plan

## Goal

Move accepted chat messages from the legacy JPA `messages` compatibility table to the partitioned PostgreSQL `chat_messages` canonical store while keeping the rollout sliced, testable, and reversible.

## Preconditions

- Phase 3 Redis Streams append, writer, fanout, pending recovery, and DLQ path are complete.
- `chat_messages`, `chat_messages_default`, and `room_storage_configs` DDL exist in `infra/postgres/message-partitions.sql`.
- WebSocket real-time delivery remains best-effort; canonical history and gap fill repair missed live delivery.

## Slices

### Slice 4.0 Message Write Port

1. Add `MessageWritePort` and message write request/result DTOs.
2. Move the current JPA `messages` write behavior into `JpaMessageWriteAdapter`.
3. Change `MessageWriterWorker` to depend on `MessageWritePort`.
4. Keep current duplicate/idempotency and DLQ behavior unchanged.
5. Verify with targeted writer tests and full Gradle tests.
6. Commit as `refactor: introduce message write port`.

### Slice 4.1 Partitioned Writer

1. Add a JdbcTemplate-backed `chat_messages` repository.
2. Add `PartitionedMessageWriteAdapter` implementing `MessageWritePort`.
3. Use batch inserts sized by worker configuration.
4. Make store target selectable by configuration.
5. Verify insert SQL, duplicate handling, and application context wiring.
6. Commit as `feat: write messages to partitioned store`.

### Slice 4.2 Storage Shard Config

1. Add `room_storage_configs` reader.
2. Calculate `writeShard = hash(messageId) % currentShardCount`.
3. Keep `writeShard`, `streamShard`, and `fanoutShard` independent.
4. Verify default shard count fallback and configured shard count behavior.
5. Commit as `feat: calculate canonical write shard`.

### Slice 4.3 Canonical History And Gap Fill

1. Add JdbcTemplate-backed canonical message read repository.
2. Convert room history and cursor history reads to canonical store.
3. Add `GET /chat-rooms/{roomId}/messages/gap` using `roomSeq`.
4. Keep legacy `messages` table as fallback only where explicitly configured.
5. Verify page/cursor/gap-fill behavior with unit and controller tests.
6. Commit as `feat: read message history from canonical store`.

### Slice 4.4 Read Replica Policy

1. Add read-only datasource/JdbcTemplate configuration.
2. Support `DB_READ_HOST=postgres-replica`.
3. Use primary fallback for latest user history when replica lag is detected.
4. Return delayed/admin notice behavior instead of silently hiding lag.
5. Verify datasource binding and repository selection.
6. Commit as `feat: add read replica history path`.

### Slice 4.5 Partition Maintenance And Archive Metadata

1. Add partition precreation job for today/tomorrow partitions.
2. Extend archive script to leave checksum metadata.
3. Preserve 100-day retention/archive target from the service design.
4. Verify archive one-shot behavior with docker compose when available.
5. Commit as `feat: add partition archive metadata`.

## Verification

Run after each slice:

```bash
./gradlew test --no-daemon
git diff --check
```

Run before final handoff:

```bash
mise run start:all
mise run verify:chat
docker compose exec -T postgres psql -U chatuser -d chatdb -Atc "select count(*) from chat_messages;"
docker compose exec -T postgres-replica psql -U chatuser -d chatdb -Atc "select pg_is_in_recovery(), count(*) from chat_messages;"
docker compose run --rm -e CHAT_PARTITION_ARCHIVE_RUN_ONCE=true postgres-partition-archive
```

## Caveats

> - Phase 4 changes the durable source of truth. Each slice must keep a narrow rollback path.
> - `writeShard` must not be coupled to Redis stream shard or fanout shard.
> - Admin keyword search remains Phase 5; Phase 4 only needs exact/cursor/gap reads from the canonical store.
