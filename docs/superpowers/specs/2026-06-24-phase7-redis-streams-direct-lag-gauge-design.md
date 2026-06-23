# Phase 7 Redis Streams Direct Lag Gauge 설계서

- 작성일: 2026-06-24
- 슬라이스: Redis Streams direct lag gauge
- 상태: 구현 완료

## 1. 문제 이해 / 요구사항 정리

### 조건

- Redis Streams append/read/claim/worker event metric은 이미 존재한다.
- Phase 7 release gate의 writer lag 조건은 Redis 서버가 직접 보고하는 backlog 신호가 필요하다.
- known stream set은 `chat:stream:rooms`에 저장되어 있다.
- metric tag cardinality는 `stream_shard`, `consumer_group`처럼 bounded 값으로 제한한다.

### 목표

- `XINFO GROUPS` 기반 group lag gauge를 추가한다.
- `XPENDING` summary 기반 pending gauge를 추가한다.
- room stream key별 값을 `stream_shard`, `consumer_group` 단위로 합산한다.
- worker scheduler에서 configurable interval로 poll한다.
- direct lag gauge의 설정과 운영 문서를 추가한다.

### 비범위

- Prometheus alert rule 파일은 추가하지 않는다.
- Redis exporter 연동은 구현하지 않는다.
- room별 lag/pending metric은 추가하지 않는다.

## 2. 해결 접근

### 선택한 접근

`SpringRedisStreamLagReader`가 Redis 상태를 읽고, `RedisStreamLagMonitor`가 shard/group 단위로 합산한 뒤 `MessageStreamMetrics`가 gauge state를 갱신한다.

### 이유

- Redis 접근, 합산, Micrometer 등록 책임이 분리되어 테스트하기 쉽다.
- worker application의 기존 scheduler 안에서 polling을 제어할 수 있다.
- room key를 tag로 쓰지 않아 TSDB cardinality를 제한한다.

## 3. Metric Contract

| Metric | Type | Tags | 의미 |
| --- | --- | --- | --- |
| `chat.redis.stream.group.lag` | Gauge | `stream_shard`, `consumer_group` | `XINFO GROUPS` lag 합계 |
| `chat.redis.stream.group.pending` | Gauge | `stream_shard`, `consumer_group` | `XPENDING` summary pending 합계 |

## 4. 테스트 전략

- reader 테스트에서 known stream key, `XINFO GROUPS`, `XPENDING` summary를 읽어 snapshot을 만드는지 검증한다.
- monitor 테스트에서 같은 shard/group의 여러 stream snapshot을 합산하는지 검증한다.
- scheduler 테스트에서 direct lag monitor polling enabled/disabled를 검증한다.
- module/full test로 Spring wiring과 compile 영향을 확인한다.

## 5. 복잡도

- polling 시간 복잡도: `O(S * G)`
- gauge 갱신 시간 복잡도: `O(S * G)`
- gauge 공간 복잡도: `O(H * G)`

여기서 `S`는 known stream 수, `G`는 consumer group 수, `H`는 stream shard 수다.

## 6. 주의사항

> - Redis `lag` 필드가 없는 버전/상태에서는 lag gauge가 갱신되지 않을 수 있다.
> - pending count는 재처리 가능 메시지 수가 아니라 pending entry summary다.
> - polling interval을 낮추면 Redis 명령 수가 `known stream 수 * consumer group 수`만큼 늘어난다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| worker application poller | release gate 의미를 코드와 문서에서 직접 고정한다 | Redis polling 비용을 관리해야 한다 | 선택 |
| Redis exporter | 앱 코드가 단순하다 | consumer group 설정과 tag cardinality 통제가 외부화된다 | 보조 |
| event metric 기반 derived signal | 추가 Redis 명령이 없다 | direct backlog gauge가 아니다 | 제외 |

## 8. 후속 질문

- direct lag gauge alert threshold와 evaluation window를 문서화할 것인가?
- 별도 observer worker role이 필요할 만큼 stream 수가 증가할 것인가?
