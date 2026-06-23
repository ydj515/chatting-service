# Phase 7 슬라이스 문서 인덱스

Phase 7은 슬라이스별로 독립 설계 문서를 먼저 작성한 뒤 구현한다. 이 문서는 각 슬라이스 문서의 위치와 상태를 추적한다.

## 1. 문서 규칙

- 각 슬라이스는 `docs/superpowers/specs/YYYY-MM-DD-phase7-<topic>-design.md` 형식의 독립 설계 문서를 가진다.
- 하나의 문서에 여러 슬라이스를 섞지 않는다.
- 슬라이스 설계 문서에는 조건, 목표, 접근안, 복잡도, 주의사항, 대안, 후속 질문을 포함한다.
- 구현 계획은 설계 승인 후 `docs/superpowers/plans/YYYY-MM-DD-phase7-<topic>.md`로 별도 작성한다.
- 구현 완료 후 결과가 release gate나 운영 절차를 바꾸면 해당 슬라이스 문서 또는 관련 운영 문서를 갱신한다.

## 2. 현재 슬라이스

| 순서 | 슬라이스 | 설계 문서 | 상태 |
| --- | --- | --- | --- |
| 1 | Nginx stale upstream synthetic check | [설계](./superpowers/specs/2026-06-21-phase7-nginx-role-routing-design.md), [계획](./superpowers/plans/2026-06-21-phase7-nginx-role-routing-check.md), [운영 문서](./phase7_nginx_role_routing_check.md) | 구현 완료 |
| 2 | Takeover smoke routing check opt-in integration | [설계](./superpowers/specs/2026-06-21-phase7-takeover-routing-check-integration-design.md), [계획](./superpowers/plans/2026-06-21-phase7-takeover-routing-check-integration.md), [운영 문서](./phase7_nginx_role_routing_check.md) | 구현 완료 |
| 3 | Fanout owner kill takeover summary 확장 | [설계](./superpowers/specs/2026-06-23-phase7-fanout-takeover-summary-design.md), [계획](./superpowers/plans/2026-06-23-phase7-fanout-takeover-summary.md), [운영 문서](./phase7_fanout_takeover_summary.md) | 구현 완료 |
| 4 | WebSocket reconnect synthetic load test | [설계](./superpowers/specs/2026-06-24-phase7-websocket-reconnect-load-test-design.md), [계획](./superpowers/plans/2026-06-24-phase7-websocket-reconnect-load-test.md), [운영 문서](./phase7_reconnect_load_test_scenarios.md) | 구현 완료 |
| 5 | Redis Streams lag/pending 및 writer latency 계측 | [설계](./superpowers/specs/2026-06-24-phase7-redis-streams-metrics-design.md), [계획](./superpowers/plans/2026-06-24-phase7-redis-streams-metrics.md), [운영 문서](./observability_metrics.md) | 구현 완료 |
| 6 | Admin search warm/cold latency gate | [설계](./superpowers/specs/2026-06-24-phase7-admin-search-latency-gate-design.md), [계획](./superpowers/plans/2026-06-24-phase7-admin-search-latency-gate.md), [운영 문서](./phase7_admin_search_latency_gate.md) | 구현 완료 |
| 7 | Redis Streams direct lag gauge | [설계](./superpowers/specs/2026-06-24-phase7-redis-streams-direct-lag-gauge-design.md), [계획](./superpowers/plans/2026-06-24-phase7-redis-streams-direct-lag-gauge.md), [운영 문서](./phase7_redis_streams_direct_lag_gauge.md) | 구현 완료 |
| 8 | Chaos test runbook | [설계](./superpowers/specs/2026-06-24-phase7-chaos-test-runbook-design.md), [계획](./superpowers/plans/2026-06-24-phase7-chaos-test-runbook.md), [운영 문서](./phase7_chaos_test_runbook.md) | 구현 완료 |
| 9 | WebSocket reconnect chaos orchestration | [설계](./superpowers/specs/2026-06-24-phase7-websocket-reconnect-chaos-orchestration-design.md), [계획](./superpowers/plans/2026-06-24-phase7-websocket-reconnect-chaos-orchestration.md), [운영 문서](./phase7_reconnect_chaos_orchestration.md) | 구현 완료 |

## 3. 다음 후보 슬라이스

| 후보 | 슬라이스 | 문서 작성 전 확인할 점 |
| --- | --- | --- |
| C | Admin search slow query plan capture | cold p99 실패 시 `EXPLAIN (ANALYZE, BUFFERS)` 수집 조건 |
| D | Redis Streams lag alert rule | `lag`/`pending` threshold와 evaluation window |
| E | Reconnect chaos recovery SLO gate | 주입→storm 완료 wall-clock SLO 기준선 |

## 4. 복잡도

- 인덱스 갱신 시간 복잡도: `O(S)`
- 슬라이스 문서 탐색 시간 복잡도: `O(S)`
- 공간 복잡도: `O(S)`

여기서 `S`는 Phase 7 슬라이스 수다.

## 5. 주의사항

> - 이 인덱스는 진행 상황을 추적하는 문서이고, 각 슬라이스의 상세 설계는 반드시 별도 문서에 둔다.
> - 한 슬라이스가 너무 커지면 다시 작은 슬라이스로 쪼갠다.
> - Phase7-pre 또는 Phase8 범위가 섞이면 해당 항목은 별도 문서로 분리한다.

## 6. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 슬라이스별 독립 문서 + 인덱스 | 변경 추적과 승인 단위가 명확하다 | 문서 수가 늘어난다 | 기본 |
| 하나의 Phase 7 통합 문서 | 전체를 한 번에 보기 쉽다 | 슬라이스별 변경 이력이 흐려진다 | 보조 자료로만 사용 |
| 구현 계획에 설계를 함께 작성 | 파일 수가 적다 | 구현 전에 운영 기준을 검토하기 어렵다 | 사용하지 않음 |

## 7. 후속 질문

- Redis Streams direct lag gauge threshold를 alert rule로 고정할 것인가?
- Admin search cold p99 실패 시 slow query plan capture를 runner에 붙일 것인가?
- 슬라이스 완료 상태를 `설계`, `계획`, `구현`, `검증` 단계로 더 세분화할 것인가?
