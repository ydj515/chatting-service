# Phase 7 Redis Streams Direct Lag Gauge

이 문서는 Redis Streams consumer group lag/pending direct gauge의 의미와 운영 기준을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- 기존 Redis Streams metric은 append/read/claim/worker 처리 이벤트를 기록한다.
- Phase 7 release gate는 Redis 서버가 보고하는 consumer group backlog를 직접 관측해야 한다.
- 모든 room stream key나 `roomId`를 metric tag로 넣으면 hot room 상황에서 TSDB cardinality가 커진다.

### 목표

- worker application이 known stream set을 주기적으로 순회한다.
- 각 stream의 `XINFO GROUPS`에서 consumer group `lag`와 `pending` count를 읽는다.
- `stream_shard`, `consumer_group` 단위로 합산해 Micrometer gauge로 노출한다.

## 2. Metric Contract

| Metric | Type | Tags | 의미 |
| --- | --- | --- | --- |
| `chat.redis.stream.group.lag` | Gauge | `stream_shard`, `consumer_group` | Redis `XINFO GROUPS` group lag 합계 |
| `chat.redis.stream.group.pending` | Gauge | `stream_shard`, `consumer_group` | Redis `XINFO GROUPS` group pending 합계 |

`stream_shard`는 stream key에서 파싱한 bounded shard 값이다. stream key 형식이 예상과 다르면 `unknown` tag로 기록한다.

## 3. 설정

Docker profile 기본값:

```yaml
chat:
  worker:
    redis-stream-lag:
      enabled: true
      poll-delay-millis: 5000
```

환경 변수:

| 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `CHAT_WORKER_REDIS_STREAM_LAG_ENABLED` | `true` | direct lag gauge polling 활성화 |
| `CHAT_WORKER_REDIS_STREAM_LAG_POLL_DELAY_MILLIS` | `5000` | polling fixed delay |

## 4. 복잡도

- polling 시간 복잡도: `O(S * G)`
- gauge 갱신 시간 복잡도: `O(S * G)`
- gauge 공간 복잡도: `O(H * G)`

여기서 `S`는 known stream 수, `G`는 관측 consumer group 수, `H`는 stream shard 수다.

## 5. 주의사항

> - `lag`는 Redis 서버의 `XINFO GROUPS` 결과에 `lag` 필드가 있을 때만 갱신된다. Redis 버전이나 group 상태에 따라 필드가 없으면 pending gauge만 갱신될 수 있다.
> - `pending`은 `XINFO GROUPS`에서 조회한 pending count 기준이며, claim 가능한 메시지 수와 같지 않다.
> - room stream key와 `roomId`는 metric tag에 넣지 않는다. 상세 추적은 로그나 tracing으로 분리한다.
> - polling interval을 너무 낮추면 Redis에 `XINFO` 부하가 생긴다.

## 6. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| application poller | worker group과 같은 설정으로 bounded tag metric을 직접 노출한다 | known stream 수에 비례해 Redis 명령이 증가한다 | 선택 |
| Redis exporter/Prometheus query | 애플리케이션 코드가 단순하다 | worker consumer group 설정과 release gate 의미를 코드에서 보장하기 어렵다 | 보조 |
| 기존 event metric만 사용 | Redis 추가 polling이 없다 | 실제 backlog gauge가 없어 release gate 판단이 간접적이다 | 제외 |

## 7. 후속 질문

- `lag`와 `pending` alert threshold를 각각 어떤 evaluation window로 둘 것인가?
- worker가 아닌 별도 observer role로 polling을 분리할 것인가?
- Redis Cluster 환경에서 known stream set shard scan 비용을 별도 synthetic check로 검증할 것인가?
