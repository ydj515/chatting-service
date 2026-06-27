# Phase 8.7 Redis Streams MAXLEN과 roomSeq gap audit 설계

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 8.2부터 Docker backend의 기본 Redis topology는 Redis Cluster다.
- Redis Cluster node는 `appendonly yes`, `appendfsync everysec`를 사용하므로 node 또는 host crash 시 마지막 fsync 이후 최대 1초의 Redis ingest가 손실될 수 있다.
- Phase 8.4부터 메시지 수락 경로는 `room_storage_configs`의 shard 설정을 읽어 Redis Stream shard와 PostgreSQL `chat_messages` shard 컬럼을 결정한다.
- 현재 `RedisMessageStreamProducer.append()`는 Redis Streams에 무제한 `XADD`를 수행한다.
- 현재 `RedisStreamLagMonitor`는 Redis consumer group lag/pending metric을 제공하지만, canonical store의 `room_seq` 유실 구간을 직접 감지하지 않는다.
- WebSocket heartbeat는 같은 Phase 8.7 생존성 묶음에 속하지만, 이번 PR에서는 제외하고 다음 작은 PR로 분리한다.

### 목표

- Redis Streams append에 bounded `MAXLEN` 정책을 적용해 worker 정체나 HPA 지연 시 Redis OOM 위험을 낮춘다.
- `MAXLEN` trim 또는 Redis 장애로 메시지가 canonical PostgreSQL store까지 도달하지 못한 경우, `roomSeq` gap audit로 유실 구간을 감지한다.
- gap audit 결과를 낮은 cardinality의 Micrometer metric으로 노출해 Prometheus alert와 release gate에서 사용할 수 있게 한다.
- Compose와 K8s 전환 양쪽에서 재사용 가능한 설정과 worker 구조를 유지한다.
- Phase 8.7 운영 문서에 `MAXLEN`은 복구가 아니라 backpressure이며, gap audit은 감지 경로라는 점을 명확히 남긴다.

### 비범위

- WebSocket heartbeat, ping/pong timeout, zombie session 회수는 다음 PR에서 구현한다.
- gap을 자동 복구하거나 누락 메시지를 재생성하지 않는다.
- per-room high-cardinality metric을 기본 노출하지 않는다.
- admin API로 gap 상세 목록을 조회하는 기능은 이번 PR 범위가 아니다.
- gap audit 결과를 별도 DB 테이블에 영속 저장하지 않는다.

## 2. 해결 접근

### 선택한 접근

Redis append 시점에 approximate `MAXLEN`을 적용하고, worker 애플리케이션에 scheduled `RoomSeqGapAuditWorker`를 추가한다.

- `ChatRedisProperties.Streams`에 `maxLen`과 `maxLenApproximate`를 추가한다.
- `RedisMessageStreamProducer.append()`는 `StreamRecords.newRecord().ofMap(fields).withStreamKey(streamKey)` 형태로 전환하고, configured `maxLen`이 양수이면 `XADD ... MAXLEN` 옵션을 적용한다.
- 신규 repository는 PostgreSQL `chat_messages`에서 최근 audit window의 `room_seq` 인접 차이를 window function으로 계산한다.
- 신규 worker는 repository 결과를 aggregate metric으로 갱신한다.
- 신규 metric은 cardinality를 통제하기 위해 전체 aggregate 중심으로 둔다.

이 접근은 Redis memory bound와 유실 감지를 한 PR에서 연결하되, gap 상세 저장/API와 heartbeat를 제외해 구현과 검증을 작게 유지한다.

## 3. 컴포넌트 설계

### Redis Streams MAXLEN 설정

`ChatRedisProperties.Streams`에 다음 설정을 추가한다.

```kotlin
data class Streams(
    val roomStreamKeyPrefix: String = "chat:stream:room:",
    val knownStreamsKey: String = "chat:stream:rooms",
    val deadLetterStreamKeyPrefix: String = "chat:stream:dlq:",
    val shardCount: Int = 1,
    val maxLen: Long = 1_000_000,
    val maxLenApproximate: Boolean = true,
)
```

환경 변수:

- `CHAT_REDIS_STREAMS_MAX_LEN`
- `CHAT_REDIS_STREAMS_MAX_LEN_APPROXIMATE`

`maxLen <= 0`이면 `MAXLEN`을 비활성화한다. 기본값은 운영 안전을 위해 1,000,000 entries로 둔다. 실제 운영에서는 메시지 크기, Redis memory limit, worker 처리량을 보고 조정한다.

### RedisMessageStreamProducer

현재 producer는 다음 흐름을 유지한다.

1. room stream key 계산
2. envelope field map 구성
3. Redis Stream append
4. known streams set 등록
5. append latency metric 기록

변경 후 append만 bounded stream append로 바꾼다. `MAXLEN` 적용 실패는 message accept 실패로 이어져야 하므로 기존 failure path를 그대로 사용한다.

정책:

- `maxLen > 0`이면 `MAXLEN`을 적용한다.
- `maxLenApproximate = true`이면 approximate trim을 사용한다.
- `maxLenApproximate = false`이면 exact trim을 사용한다.
- append가 성공했지만 known stream set 등록이 실패하면 기존처럼 실패로 처리한다.

### Gap Audit Repository

신규 `RoomSeqGapAuditRepository`를 둔다.

역할:

- 최근 audit window 내 canonical `chat_messages`를 기준으로 `room_seq` gap을 계산한다.
- deleted 여부와 무관하게 유실 감지 기준은 `room_seq` 존재 여부다. soft-deleted 메시지는 canonical row가 존재하므로 gap으로 보지 않는다.
- aggregate 결과만 반환한다.

반환 모델:

```kotlin
data class RoomSeqGapAuditSummary(
    val roomCountWithGaps: Long,
    val missingSequenceCount: Long,
    val maxGapWidth: Long,
    val scannedRoomCount: Long,
)
```

SQL 개념:

```sql
WITH recent_rooms AS (
    SELECT DISTINCT room_id
    FROM chat_messages
    WHERE created_at >= ?
),
candidate_rows AS (
    SELECT cm.room_id, cm.room_seq, cm.created_at
    FROM chat_messages cm
    JOIN recent_rooms rr ON rr.room_id = cm.room_id
    WHERE cm.created_at >= ?

    UNION ALL

    SELECT predecessor.room_id, predecessor.room_seq, predecessor.created_at
    FROM recent_rooms rr
    JOIN LATERAL (
        SELECT cm.room_id, cm.room_seq, cm.created_at
        FROM chat_messages cm
        WHERE cm.room_id = rr.room_id
          AND cm.created_at < ?
        ORDER BY cm.room_seq DESC
        LIMIT 1
    ) predecessor ON true
),
ordered AS (
    SELECT
        room_id,
        created_at,
        room_seq,
        lag(room_seq) OVER (PARTITION BY room_id ORDER BY room_seq) AS previous_room_seq
    FROM candidate_rows
),
gaps AS (
    SELECT
        room_id,
        room_seq - previous_room_seq - 1 AS gap_width
    FROM ordered
    WHERE created_at >= ?
      AND previous_room_seq IS NOT NULL
      AND room_seq > previous_room_seq + 1
)
SELECT
    count(DISTINCT room_id) AS room_count_with_gaps,
    coalesce(sum(gap_width), 0) AS missing_sequence_count,
    coalesce(max(gap_width), 0) AS max_gap_width
FROM gaps
```

`scannedRoomCount`는 같은 window에서 `count(DISTINCT room_id)`로 계산한다.

초기 메시지가 `room_seq = 1`이 아닌 방은 이번 aggregate에서는 gap으로 보지 않는다. 그 방이 Phase 도중 생성되었거나 retention/archive 이후 일부만 남아 있을 수 있기 때문이다. room creation 시각과 current sequence counter 비교는 이번 PR에서 구현하지 않고 후속 hardening으로 둔다.

### RoomSeqGapAuditWorker

worker 애플리케이션에 scheduled poll을 추가한다.

설정:

```kotlin
data class RoomSeqGapAudit(
    val enabled: Boolean = true,
    val pollDelayMillis: Long = 60_000,
    val lookback: Duration = Duration.ofMinutes(5),
)
```

환경 변수:

- `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_ENABLED`
- `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_POLL_DELAY_MILLIS`
- `CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_LOOKBACK`

worker는 `workerProperties.roomSeqGapAudit.enabled`가 true이고 `WORKER_ROLES`에 `room-seq-gap-audit`이 있을 때만 실행한다. 실패 시 exception을 삼키고 warn log를 남긴다. audit 실패 자체가 message writer/fanout 경로를 막으면 안 된다.

### Gap Audit Metrics

신규 metric은 `MessageStreamMetrics`에 추가하거나 별도 `RoomSeqGapAuditMetrics`로 분리한다. 책임이 Redis Stream worker metric과 다르므로 별도 클래스를 추천한다.

Metric:

- `chat.room_seq.gap.rooms`: gap이 하나 이상 있는 room 수
- `chat.room_seq.gap.missing_sequences`: gap width 합계
- `chat.room_seq.gap.max_width`: 가장 큰 단일 gap width
- `chat.room_seq.gap.scanned_rooms`: audit window에서 스캔한 room 수

모두 gauge로 둔다. label은 두지 않는다. `roomId` label은 기본 metric에 넣지 않는다.

## 4. 데이터 흐름

### 메시지 수락과 Redis append

1. `ChatServiceImpl.sendMessage()`가 admission/moderation/sanction gate를 통과한다.
2. Redis sequence가 `roomSeq`를 발급한다.
3. `RedisMessageStreamProducer.append()`가 stream shard key에 envelope를 append한다.
4. append 시 `MAXLEN`이 적용되어 오래된 stream entry가 Redis 내부 정책에 따라 trim될 수 있다.
5. append 성공 후 client는 `MESSAGE_ACCEPTED` 또는 REST accepted response를 받는다.

### Worker 처리

1. message writer worker가 Redis Stream에서 record를 읽는다.
2. record가 남아 있고 worker가 처리하면 PostgreSQL `chat_messages`에 canonical row가 저장된다.
3. worker가 처리하기 전에 Redis Stream entry가 trim되거나 Redis 장애로 ingest가 유실되면 해당 `roomSeq`는 canonical row로 도달하지 못할 수 있다.

### Gap audit

1. `RoomSeqGapAuditWorker`가 주기적으로 최근 lookback window를 조회한다.
2. repository가 room별 `room_seq` 인접 차이를 계산한다.
3. worker가 aggregate metric을 갱신한다.
4. Prometheus alert 또는 release gate가 gap metric을 보고 유실 여부를 판단한다.

## 5. 오류 처리와 운영 정책

- Redis `XADD MAXLEN` 실패: 기존 append 실패와 동일하게 message accept 실패로 처리한다.
- known streams set 등록 실패: 기존 정책과 동일하게 append 실패로 처리한다.
- gap audit SQL 실패: warn log를 남기고 기존 metric 값을 유지한다.
- gap 발견: request path를 차단하지 않는다. metric/alert로 운영자가 인지한다.
- lookback window 밖의 gap: 이번 audit에서는 보지 않는다. 장기 forensic은 cold archive/admin export 계열 후속으로 둔다.

## 6. 설정과 문서

### application-docker.yml

추가 설정:

```yaml
chat:
  redis:
    streams:
      max-len: ${CHAT_REDIS_STREAMS_MAX_LEN:1000000}
      max-len-approximate: ${CHAT_REDIS_STREAMS_MAX_LEN_APPROXIMATE:true}
  worker:
    room-seq-gap-audit:
      enabled: ${CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_ENABLED:true}
      poll-delay-millis: ${CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_POLL_DELAY_MILLIS:60000}
      lookback: ${CHAT_WORKER_ROOM_SEQ_GAP_AUDIT_LOOKBACK:5m}
```

### 문서 갱신

- `docs/configuration.md`: 신규 환경 변수와 운영 의미 추가
- `docs/infrastructure.md`: Phase 8.7 MAXLEN/gap audit 운영 설명 추가
- `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md`: Phase 8.7 완료 기준에서 heartbeat 분리 상태를 명시
- `README.md`: Phase 8.7 검증 명령을 추가할 때만 갱신한다. 이번 PR에서 running-stack smoke script를 만들지 않으면 README는 건드리지 않는다.

## 7. 테스트 계획

### 단위 테스트

- `RedisMessageStreamProducerTest`
  - `maxLen > 0`이면 bounded append API를 사용한다.
  - `maxLen <= 0`이면 기존 unbounded append와 동등하게 동작한다.
  - append 성공 후 known streams set 등록은 유지된다.

- `RoomSeqGapAuditRepositoryTest`
  - SQL이 `lag(room_seq) OVER (PARTITION BY room_id ORDER BY room_seq)`를 사용한다.
  - cutoff 직전 predecessor row를 포함해 audit window 첫 row와 이전 row 사이 gap을 감지한다.
  - aggregate column을 `RoomSeqGapAuditSummary`로 매핑한다.
  - `created_at >= ?` lookback cutoff를 `Instant` 기반 `Timestamp`로 바인딩한다.

- `RoomSeqGapAuditWorkerTest`
  - repository summary를 metric gauge로 반영한다.
  - repository 실패 시 exception을 밖으로 던지지 않는다.

- `RoomSeqGapAuditMetricsTest`
  - room count, missing sequence count, max gap width, scanned rooms gauge를 갱신한다.
  - 반복 갱신 시 같은 gauge holder가 최신 값으로 바뀐다.

### 통합/문법 검증

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.redis.RedisMessageStreamProducerTest --tests com.chat.persistence.repository.RoomSeqGapAuditRepositoryTest --tests com.chat.persistence.service.RoomSeqGapAuditWorkerTest --tests com.chat.persistence.service.RoomSeqGapAuditMetricsTest --no-daemon
./gradlew :chat-worker-application:test :chat-runtime-config:processResources --no-daemon
./gradlew test --no-daemon
git diff --check
```

### Running-stack smoke

후속 또는 수동 smoke로 다음을 둔다.

```bash
node scripts/verify-stream-maxlen.mjs
node scripts/verify-roomseq-gap-audit.mjs
```

이번 PR에서는 우선 단위 테스트와 설정/문서 검증을 완료 기준으로 둔다. running-stack smoke 스크립트는 구현 범위가 커지면 별도 hardening으로 분리할 수 있다.

## 8. 복잡도

- Redis append 시간 복잡도: `O(1)`에 가깝다. `MAXLEN` trim 비용은 Redis 내부 approximate/exact 정책에 따른다.
- Gap audit SQL 시간 복잡도: window 정렬 기준 `O(M log M)` 가능성이 있다. 여기서 `M`은 lookback window 내 `chat_messages` row 수다.
- Gap audit metric 갱신 시간 복잡도: `O(1)`
- Redis append 추가 공간 복잡도: `O(1)`
- Redis Streams 저장 공간 복잡도: stream key당 `O(maxLen)` 상한을 둔다.
- Gap audit metric 공간 복잡도: aggregate gauge만 유지하므로 `O(1)`

## 9. 주의사항

> - `MAXLEN`은 Redis memory 보호용 backpressure다. 메시지 보존 보장이 아니다.
> - `roomSeq` gap audit은 유실 감지 경로이며 자동 복구 경로가 아니다.
> - lookback window 밖의 gap은 이번 worker가 놓칠 수 있다. window는 운영 retention과 worker 장애 시간을 고려해 조정해야 한다.
> - `roomId`를 metric label로 노출하지 않는다. room 수가 늘면 Prometheus cardinality가 급격히 증가할 수 있다.
> - exact `MAXLEN`은 Redis trim 비용을 키울 수 있다. 기본은 approximate로 둔다.
> - sequence 발급 이후 Redis append가 실패하면 실제 수락 메시지 유실이 아니어도 `roomSeq` hole이 생길 수 있다. alert는 warning으로 시작하고 append failure, worker lag, canonical 저장 지표를 함께 확인한다.
> - heartbeat는 이번 PR에서 제외한다. zombie WebSocket connection 회수는 다음 작은 PR에서 별도로 검증한다.

## 10. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Redis `MAXLEN` + aggregate roomSeq gap audit | Redis OOM 방어와 유실 감지를 함께 제공하고 구현 범위가 작다 | gap 상세 저장과 복구는 제공하지 않는다 | 선택 |
| Redis lag/pending alert만 강화 | 구현이 작고 기존 metric을 재사용한다 | 실제 canonical `roomSeq` 유실을 식별하지 못한다 | 제외 |
| gap audit 결과를 DB table에 저장 | 운영 추적과 admin 조회가 쉽다 | DDL, retention, 중복 감지, API까지 범위가 커진다 | 후속 |
| per-room gap metric 노출 | 어느 방에서 문제가 났는지 Prometheus만으로 바로 보인다 | high-cardinality 위험이 크다 | 제외 |
| `MAXLEN` 없이 memory alert만 둠 | 유실 위험을 줄인다 | worker 정체 시 Redis OOM을 막지 못한다 | 제외 |

## 11. 후속 질문

- 다음 PR의 WebSocket heartbeat는 Spring WebSocket ping/pong frame 기반으로 할 것인가, application-level heartbeat message를 병행할 것인가?
- gap audit 상세를 admin API로 제공할 필요가 생기면 DB table 저장 방식으로 확장할 것인가?
- Redis Streams `MAXLEN` 기본값 1,000,000 entries가 10k msg/sec hot room gate에서 충분한지 running-stack smoke로 조정할 것인가?
