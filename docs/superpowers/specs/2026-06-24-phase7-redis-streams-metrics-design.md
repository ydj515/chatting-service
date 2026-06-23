# Phase 7 Redis Streams Metrics 설계서

- 작성일: 2026-06-24
- 슬라이스: Redis Streams lag/pending 및 writer latency 계측
- 상태: 구현 완료

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 3 이후 메시지 수락 기준은 Redis Streams append 성공이다.
- `MessageWriterWorker`는 Redis Streams record를 PostgreSQL canonical store에 저장하고, `HotRoomFanoutWorker`는 같은 stream을 fan-out한다.
- Phase 7 release gate는 writer lag, pending 증가, writer/fanout latency, dead-letter 증가를 운영자가 빠르게 볼 수 있어야 한다.
- metric tag에는 `roomId`, `messageId`, `clientMessageId`, raw stream key처럼 cardinality가 커질 수 있는 값을 넣지 않는다.
- 현재 코드에는 fanout owner lease metric은 있지만 Redis Streams append/read/claim/write 경로의 공통 metric은 부족하다.

### 목표

- Redis Streams append latency를 `stream_shard`와 `outcome` 기준으로 기록한다.
- consumer group read/claim record 수를 `consumer_group`, `source`, `stream_shard` 기준으로 기록한다.
- writer/fanout worker batch latency와 처리 record 수를 `worker_role`, `outcome` 기준으로 기록한다.
- max delivery 초과로 dead-letter stream에 이동한 record 수를 `consumer_group`, `stream_shard` 기준으로 기록한다.
- 테스트에서 raw stream key가 metric tag로 들어가지 않는지 확인한다.
- `docs/observability_metrics.md`와 Phase 7 슬라이스 인덱스를 실제 metric 이름에 맞게 갱신한다.

### 비범위

- Redis 서버에 직접 `XINFO GROUPS`, `XPENDING`, `XLEN`을 polling하는 backlog gauge는 이번 슬라이스에서 구현하지 않는다.
- Prometheus alert rule 파일이나 dashboard JSON은 추가하지 않는다.
- room별 top N metric은 추가하지 않는다.

## 2. 해결 접근

### 선택한 접근

`MessageStreamMetrics` 서비스를 추가하고 기존 Redis Streams 경로에 주입한다.

계측 지점:

1. `RedisMessageStreamProducer.append()`에서 append 성공/실패 latency를 기록한다.
2. `RedisMessageStreamConsumer.readNew()`에서 신규 read record 수를 shard별로 기록한다.
3. `RedisMessageStreamConsumer.claimPending()`에서 pending scan 수와 실제 claimed record 수를 분리해 기록한다.
4. `MessageWriterWorker`에서 batch 성공, 일부 성공, 실패 latency와 record 수를 기록한다.
5. `HotRoomFanoutWorker`에서 fanout 성공, 실패, lease lost latency와 record 수를 기록한다.
6. writer/fanout dead-letter 이동 직후 dead-letter counter를 기록한다.

### 이유

- 처리 경로 안에서 계측하므로 Redis 부하를 늘리는 추가 polling 없이 release gate에 필요한 1차 신호를 얻을 수 있다.
- metric 이름과 tag를 worker 공통 형식으로 맞추면 dashboard에서 writer와 fanout을 나란히 비교할 수 있다.
- raw key나 메시지 식별자를 tag로 쓰지 않아 hot room 부하 상황에서도 TSDB cardinality를 통제할 수 있다.

## 3. Metric Contract

| Metric | Type | Tags | 의미 |
| --- | --- | --- | --- |
| `chat.redis.stream.append.latency` | Timer | `stream_shard`, `outcome=success|failure` | Redis Streams append latency |
| `chat.redis.stream.consumer.records` | Counter | `consumer_group`, `source=new|pending_scanned|pending_claimed`, `stream_shard` | consumer group read/claim 관측 record 수 |
| `chat.redis.stream.worker.batch.latency` | Timer | `worker_role=message-writer|fanout`, `outcome=success|partial|failure|lease_lost` | worker batch 처리 latency |
| `chat.redis.stream.worker.records` | Counter | `worker_role=message-writer|fanout`, `outcome=success|partial|failure|lease_lost` | worker가 처리한 Redis Streams record 수 |
| `chat.redis.stream.dead_letters` | Counter | `consumer_group`, `stream_shard` | dead-letter stream 이동 수 |

`pending_scanned`는 `XPENDING` 조회 결과 중 min idle filter 적용 전 개수이고, `pending_claimed`는 실제 `XCLAIM` 후 payload mapping까지 성공한 record 수다.

## 4. 테스트 전략

- `MessageStreamMetricsTest`에서 metric 이름, tag, counter/timer 기록을 검증한다.
- producer 테스트에서 append 성공 timer가 shard/outcome tag만 갖는지 확인한다.
- writer worker 테스트에서 worker batch latency와 processed record counter를 검증한다.
- fanout worker 테스트에서 fanout batch latency와 processed record counter를 검증한다.
- focused test와 `:chat-persistence:test`로 Kotlin compile, 기존 worker 동작, Spring test context 영향을 함께 검증한다.

## 5. 복잡도

- append metric 기록 시간 복잡도: `O(1)`
- consumer record metric 집계 시간 복잡도: `O(R)`
- worker batch metric 기록 시간 복잡도: `O(1)`
- metric 추가 공간 복잡도: `O(S + G + W + O)`

여기서 `R`은 한 poll에서 mapping된 record 수, `S`는 stream shard 수, `G`는 consumer group 수, `W`는 worker role 수, `O`는 bounded outcome/source 수다.

## 6. 주의사항

> - 이번 metric은 애플리케이션 처리 이벤트와 latency를 기록한다. Redis 서버의 전체 backlog를 직접 재는 lag gauge가 아니므로, release gate에서 direct lag가 필요하면 별도 polling/exporter 슬라이스가 필요하다.
> - `pending_scanned`는 pending 후보 개수이고 실제 재처리 성공 개수가 아니다. 재처리 성공 여부는 `pending_claimed`와 worker outcome을 같이 봐야 한다.
> - `worker.records{outcome="success"}`는 worker가 ack까지 처리한 record 수이며, writer의 DB 신규 insert 수와 완전히 같지 않을 수 있다. idempotent duplicate는 처리 성공이지만 신규 insert는 0일 수 있다.
> - `lease_lost`는 fanout owner fencing에 의해 ack를 중단한 상태를 나타내며, publish 전/후 어느 지점인지 세부 분석은 기존 owner lease metric과 로그를 함께 본다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 애플리케이션 경로 계측 | 원인 지점이 명확하고 추가 Redis polling 비용이 없다 | 전체 backlog gauge를 직접 제공하지 않는다 | 선택 |
| Redis polling gauge 우선 구현 | lag/pending release gate와 직접 연결된다 | polling interval, cluster cost, exporter 중복을 먼저 결정해야 한다 | 후속 |
| Redis exporter/Prometheus query만 사용 | 애플리케이션 코드 변경이 작다 | worker outcome, partial retry, lease lost 같은 도메인 의미를 알기 어렵다 | 보조 |

## 8. 후속 질문

- direct lag gauge는 애플리케이션 poller로 구현할 것인가, Redis exporter/Prometheus query로 둘 것인가?
- worker outcome별 alert threshold를 staging 부하 테스트 후 어느 값으로 둘 것인가?
- fanout `lease_lost`를 publish 전/후 stage별 metric으로 더 쪼갤 것인가?
