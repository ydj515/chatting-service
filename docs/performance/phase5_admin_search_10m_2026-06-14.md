# Phase 5 Admin Search 10M Validation

## Summary

Phase 5 admin history/search APIs were validated against a local Docker Compose PostgreSQL dataset containing `10,001,011` `chat_messages` rows.

Target p95: `<= 1000ms`

Result: p95 target passed for both room history and keyword search.

## Environment

- Date: `2026-06-14`
- Runtime: local Docker Compose
- API base URL: `http://localhost/api`
- Admin token: `local-admin-token`
- Primary row count: `10,001,011`
- Read replica row count: `10,001,011`
- Target room: `30001`
- Target room rows: `487,800`
- Target room keyword matches: `48,780`
- Target room time range: `2026-06-13T11:46:50` to `2026-06-14T15:33:25`

## Seed Command

```bash
node scripts/seed-admin-search-messages.mjs \
  --messages 10000000 \
  --rooms normal:60,hot:30,very-hot:10 \
  --batch-size 100000 \
  --seed-id phase5_10m_20260614 \
  --psql-mode docker-compose
```

## Measurement Commands

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --requests 100 \
  --warmup 10 \
  --concurrency 5 \
  --target-p95-ms 1000 \
  --output docs/performance/phase5_admin_search_10m_2026-06-14.json
```

```bash
node scripts/measure-admin-search-p95.mjs \
  --scenario both \
  --room-id 30001 \
  --query "hello searchable admin keyword" \
  --from 2026-06-13T11:46:50 \
  --to 2026-06-14T15:33:25 \
  --requests 100 \
  --warmup 10 \
  --concurrency 5 \
  --target-p95-ms 1000 \
  --output docs/performance/phase5_admin_search_10m_time_range_2026-06-14.json
```

## Results

| Scenario | Time Range | Requests | Failures | p50 | p95 | p99 | Target |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| history | none | 100 | 0 | 14ms | 47ms | 53ms | pass |
| search | none | 100 | 0 | 499ms | 573ms | 597ms | pass |
| history | full room range | 100 | 0 | 8ms | 25ms | 29ms | pass |
| search | full room range | 100 | 0 | 531ms | 603ms | 3291ms | pass |

## Observations

- Warm p95 stayed below the 1 second Phase 5 target.
- The time-range search run had one high p99 outlier around `3.3s`.
- A cold `EXPLAIN (ANALYZE, BUFFERS)` for the same time-range search took about `6.5s` and showed heavy partition/index reads before cache warmup.
- PostgreSQL-first search is acceptable for Phase 5 p95 validation, but p99/cold-query behavior should remain a Phase 7 load/observability focus.

## Complexity

- Seed execution: `O(M)` time and stream-backpressure-bounded memory, where `M = 10,000,000`.
- History query: approximately `O(log N_room + L)`, where `L = 50`.
- Keyword search: approximately `O(log N_text + L)` when the planner uses the text indexes effectively, but cold cache and partition fan-out can increase wall-clock latency.

## Caveats

> - This is a local Docker Compose validation, not a production capacity result.
> - p95 passed, but search p99 and cold-query latency still need release-gate tracking.
> - If later hot-room/admin workloads push p95 above `1000ms`, tune PostgreSQL partition/index strategy first and then reconsider OpenSearch as the next-stage option.
