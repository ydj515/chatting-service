# Phase 7 Redis Streams Lag Alert Rule

이 문서는 Redis Streams direct lag/pending gauge를 Prometheus alert rule에 연결하는 기준을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- `chat.redis.stream.group.lag`와 `chat.redis.stream.group.pending`은 `stream_shard`, `consumer_group`만 tag로 가진다.
- Micrometer Prometheus export에서는 각각 `chat_redis_stream_group_lag`, `chat_redis_stream_group_pending`으로 노출된다.
- Redis Streams backlog는 순간적으로 생길 수 있으므로 threshold와 evaluation window를 함께 고정해야 한다.
- room stream key나 `roomId`는 alert label에 포함하지 않는다.

### 목표

- Redis Streams lag/pending warning과 critical alert를 Prometheus rule 파일로 제공한다.
- alert label cardinality를 `consumer_group`, `stream_shard`로 제한한다.
- staging 부하 테스트 전 기준선을 명확히 문서화한다.

## 2. Rule Artifact

Prometheus rule 파일:

```text
infra/prometheus/rules/phase7-redis-streams-lag.rules.yml
```

테스트 가능한 renderer:

```text
scripts/lib/phase7RedisStreamsAlertRules.mjs
```

## 3. Alert Contract

| Alert | Expr | For | Severity | 의미 |
| --- | --- | --- | --- | --- |
| `RedisStreamsGroupLagSustained` | `max by (consumer_group, stream_shard) (chat_redis_stream_group_lag) > 0` | `3m` | `warning` | lag가 transient가 아니라 지속됨 |
| `RedisStreamsGroupLagCritical` | `max by (consumer_group, stream_shard) (chat_redis_stream_group_lag) > 1000` | `5m` | `critical` | backlog가 release-blocking 수준으로 커짐 |
| `RedisStreamsGroupPendingSustained` | `max by (consumer_group, stream_shard) (chat_redis_stream_group_pending) > 0` | `5m` | `warning` | ack되지 않은 pending entry가 지속됨 |
| `RedisStreamsGroupPendingCritical` | `max by (consumer_group, stream_shard) (chat_redis_stream_group_pending) > 100` | `10m` | `critical` | pending recovery가 장시간 따라잡지 못함 |

## 4. 운영 절차

1. `infra/prometheus/rules/phase7-redis-streams-lag.rules.yml`를 Prometheus rule path에 배치한다.
2. Prometheus reload 전에 `promtool check rules`로 문법을 확인한다.
3. `RedisStreamsGroupLagSustained`는 worker 처리량, Redis latency, stream shard skew를 확인한다.
4. `RedisStreamsGroupPendingSustained`는 worker pending claim, max delivery, dead-letter 증가 여부를 같이 본다.
5. critical alert가 발생하면 Phase 7 release gate를 blocking으로 본다.

## 5. 복잡도

- alert evaluation 시간 복잡도: `O(H * G)`
- rule rendering 시간 복잡도: `O(R)`
- rule file 공간 복잡도: `O(R)`

여기서 `H`는 stream shard 수, `G`는 consumer group 수, `R`은 alert rule 수다.

## 6. 주의사항

> - `lag`는 entry count이며 시간 단위 lag가 아니다. 운영에서 실제 초 단위 지연을 보려면 처리량과 함께 해석해야 한다.
> - warning threshold는 staging 출발점이다. 정상 burst에서 오탐이 많으면 evaluation window를 먼저 늘리고, critical threshold는 부하 테스트 결과로 보정한다.
> - `pending > 0`은 항상 장애를 의미하지 않는다. 지속 시간과 `pending_claimed`, dead-letter counter를 함께 확인한다.
> - room stream key, roomId, raw Redis key는 alert label로 올리지 않는다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Prometheus rule artifact | 운영 도구와 직접 연결되고 review 가능한 threshold가 남는다 | Prometheus 배포 연결은 별도 환경 작업이 필요하다 | 선택 |
| chaos runner threshold만 사용 | 로컬 synthetic gate와 가깝다 | 상시 운영 alert가 없다 | 보조 |
| Grafana dashboard threshold만 사용 | 시각화가 쉽다 | release blocking alert contract로 검증하기 어렵다 | 보류 |

## 8. 후속 질문

- staging 부하 테스트 후 critical lag threshold를 1000에서 조정할 것인가?
- alertmanager route와 paging policy를 별도 slice로 둘 것인가?
- Redis Streams alert를 chaos recovery SLO gate와 연결할 것인가?
