# Phase 7 Redis Streams Lag Alert Rule 설계서

- 작성일: 2026-06-24
- 슬라이스: Redis Streams lag alert rule
- 상태: 구현 완료

## 1. 문제 이해 / 요구사항 정리

### 조건

- Redis Streams direct lag gauge 슬라이스에서 `chat.redis.stream.group.lag`와 `chat.redis.stream.group.pending`을 추가했다.
- 두 metric은 `stream_shard`, `consumer_group`만 tag로 가진 bounded-cardinality gauge다.
- Phase 7 release gate는 lag/pending이 지속적으로 쌓이는 상황을 alert로 확인할 수 있어야 한다.
- 직접 Prometheus 배포 stack은 아직 없으므로, rule artifact와 contract test를 먼저 제공한다.

### 목표

- Prometheus alert rule 파일을 추가한다.
- warning/critical threshold와 evaluation window를 명시한다.
- roomId, stream key 같은 high-cardinality label을 alert에 포함하지 않는다.
- rule file과 renderer가 drift 나지 않도록 테스트한다.

### 비범위

- Prometheus container나 Alertmanager route를 compose에 추가하지 않는다.
- Grafana dashboard JSON은 추가하지 않는다.
- threshold를 production 최종값으로 확정하지 않는다.

## 2. 해결 접근

### 선택한 접근

`scripts/lib/phase7RedisStreamsAlertRules.mjs`에 alert rule contract를 객체로 두고, `infra/prometheus/rules/phase7-redis-streams-lag.rules.yml`을 같은 renderer 출력과 일치시키는 방식으로 관리한다.

Alert set:

1. lag sustained warning: `lag > 0 for 3m`
2. lag critical: `lag > 1000 for 5m`
3. pending sustained warning: `pending > 0 for 5m`
4. pending critical: `pending > 100 for 10m`

### 이유

- Prometheus rule은 운영 artifact로 바로 재사용할 수 있다.
- JS renderer와 테스트를 두면 rule file의 threshold, label, metric name drift를 빠르게 잡을 수 있다.
- 기존 Node script test 흐름에 자연스럽게 포함된다.

## 3. Alert Contract

| Alert | Metric | For | Severity |
| --- | --- | --- | --- |
| `RedisStreamsGroupLagSustained` | `chat_redis_stream_group_lag > 0` | `3m` | `warning` |
| `RedisStreamsGroupLagCritical` | `chat_redis_stream_group_lag > 1000` | `5m` | `critical` |
| `RedisStreamsGroupPendingSustained` | `chat_redis_stream_group_pending > 0` | `5m` | `warning` |
| `RedisStreamsGroupPendingCritical` | `chat_redis_stream_group_pending > 100` | `10m` | `critical` |

모든 expression은 `max by (consumer_group, stream_shard)` aggregation을 사용한다.

## 4. 테스트 전략

- alert rule 4개가 warning/critical threshold와 window를 갖는지 검증한다.
- rendered YAML이 `consumer_group`, `stream_shard`만 사용하고 raw stream key나 roomId를 포함하지 않는지 검증한다.
- `infra/prometheus/rules/phase7-redis-streams-lag.rules.yml` 파일이 renderer 출력과 일치하는지 검증한다.

## 5. 복잡도

- alert evaluation 시간 복잡도: `O(H * G)`
- renderer 시간 복잡도: `O(R)`
- renderer 공간 복잡도: `O(R)`

여기서 `H`는 stream shard 수, `G`는 consumer group 수, `R`은 alert rule 수다.

## 6. 주의사항

> - `lag` gauge는 entry count이며 초 단위 지연이 아니다.
> - threshold는 staging 출발점이다. production 최종값은 부하 테스트와 정상 burst 관측 후 조정한다.
> - pending warning은 장애 단정이 아니라 pending claim/retry 경로를 확인하라는 신호다.
> - high-cardinality label을 alert rule에 추가하면 Prometheus 비용과 alert fan-out이 커질 수 있다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Prometheus rule artifact + renderer test | 운영 artifact와 테스트 가능한 contract를 동시에 확보한다 | Prometheus deployment wiring은 별도다 | 선택 |
| 문서에 threshold만 기록 | 구현 변경이 작다 | 실제 rule drift를 잡지 못한다 | 보류 |
| chaos runner threshold만 유지 | synthetic recovery gate와 직접 연결된다 | 상시 alert가 없다 | 보조 |

## 8. 후속 질문

- Alertmanager routing과 severity policy를 별도 slice로 구현할 것인가?
- staging load 결과로 `lag > 1000`, `pending > 100` 기준을 조정할 것인가?
- Redis Streams alert firing 여부를 chaos runner summary에 포함할 것인가?
