# Admin Search PostgreSQL Tuning Result

## Summary

PostgreSQL-first admin search tuning was validated against the existing local Docker Compose dataset containing `10,001,011` `chat_messages` rows.

Target warm p95: `<= 1000ms`

Final warm result: passed.

## Conditions

- Date: `2026-06-14`
- Runtime: local Docker Compose
- API base URL: `http://localhost/api`
- Admin token: `local-admin-token`
- Search mode: `FTS`
- Target room: `30001`
- Target room rows: `487,800`
- Target room keyword matches: `48,780`
- Time range: `2026-06-13T11:46:50` to `2026-06-14T15:33:25`
- PostgreSQL DDL applied:
  - `btree_gin`
  - `GIN(room_id int8_ops, content_tsv)` on default and daily hash partitions

## Changes Validated

- Default admin search now uses PostgreSQL FTS only.
- `mode=CONTAINS` is an explicit substring fallback.
- `client-admin` and measurement scripts expose search mode.
- PostgreSQL partition DDL creates room-aware FTS indexes for new partitions.

## Commands

```bash
docker compose exec -T -e PGPASSWORD=chatpass postgres \
  psql -U chatuser -d chatdb -v ON_ERROR_STOP=1 \
  < infra/postgres/message-partitions.sql
```

```bash
docker compose exec -T -e PGPASSWORD=chatpass postgres \
  psql -U chatuser -d chatdb -v ON_ERROR_STOP=1 -Atc \
  "SELECT create_chat_messages_daily_partition(date '2026-06-13', 16);
   SELECT create_chat_messages_daily_partition(date '2026-06-14', 16);
   ANALYZE chat_messages;"
```

```bash
docker compose up -d --build --wait --wait-timeout 180 chat-admin-app-1
docker compose restart nginx
docker compose up -d --wait --wait-timeout 60 nginx
```

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --from 2026-06-13T11:46:50 \
  --to 2026-06-14T15:33:25 \
  --search-mode FTS \
  --requests 100 \
  --warmup 30 \
  --concurrency 5 \
  --target-p95-ms 1000 \
  --output docs/performance/admin_search_postgres_tuning_warm_both_2026-06-14.json
```

## Results

| Run | Scenario | Warmup | Requests | Failures | p50 | p95 | p99 | Max | Target |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| post-restart | history | 10 | 100 | 0 | 9ms | 23ms | 30ms | 31ms | pass |
| post-restart | search | 10 | 100 | 0 | 477ms | 1326ms | 5797ms | 5798ms | fail |
| warm repeat | search | 10 | 100 | 0 | 476ms | 527ms | 531ms | 532ms | pass |
| final warm | history | 30 | 100 | 0 | 8ms | 14ms | 15ms | 18ms | pass |
| final warm | search | 30 | 100 | 0 | 474ms | 490ms | 490ms | 490ms | pass |

## EXPLAIN Summary

App-equivalent timestamp `EXPLAIN (ANALYZE, BUFFERS)` for the room-scoped FTS query executed in about `2.0s`.

Phase 5 baseline cold query was about `6.5s`, so cold execution improved materially. The plan still reads many FTS candidates and then filters/rechecks `room_id` and time on several partitions, which explains why warm p95 improved while cold outliers remain visible.

## Complexity

- Warm FTS search: approximately `O(log N_text + K log K)` for the matching candidate set that PostgreSQL returns before top-N sorting, where `K` is matched/rechecked rows.
- Room history: approximately `O(log N_room + L)`, where `L` is page size.
- Added room-aware FTS index storage: `O(N)` rows.

## Caveats

> - Local Docker Compose results are regression signals, not production SLO proof.
> - The first post-restart measurement can include app connection pool warmup, DB page cache warmup, and planner/cache effects.
> - PostgreSQL FTS still has a cold p99 risk for high-match hot-room queries; Phase 7 should track slow query plans and cold-start p99 separately from steady-state p95.
> - `mode=CONTAINS` was not optimized in this task and should be treated as an operator fallback, not the default path.

## Alternatives

| Option | Pros | Cons | Current Decision |
| --- | --- | --- | --- |
| Continue PostgreSQL tuning | Lower operating complexity, one canonical datastore, current warm p95 passes | Cold p99 and high-match queries still need direct tuning | Continue |
| Add room-aware trigram indexes | Could improve `CONTAINS` fallback | More write amplification and storage | Defer |
| Add OpenSearch | Better search ranking and tail-latency headroom | Adds indexing pipeline, consistency lag, and cluster operations | Revisit only if Phase 7 SLO fails |
