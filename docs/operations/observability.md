# 관측 지표와 판단 기준

Metric 등록, 실제 scrape, threshold 충족, notification 수신은 서로 다른 검증이다. 로컬 Compose에는 Prometheus/Grafana/Alertmanager 설정이 있으며 실제 배포에서 수집과 전달을 확인해야 한다.

## 현재 애플리케이션 지표

아래 이름은 Micrometer 이름이다. Prometheus에서는 점이 underscore로 변환되고 counter/timer suffix가 붙는다. 상세 tag와 집계는 구현 및 각 runbook을 따른다.

| 영역 | Metric | 해석 |
| --- | --- | --- |
| Ticket | `chat.websocket.ticket.events`, `chat.websocket.ticket.issue.latency`, `chat.websocket.ticket.rate_limit.script.failures` | 발급·소비·거부·script 오류 |
| Gateway | `chat.websocket.gateway.connections`, `chat.websocket.gateway.room.subscriptions`, `chat.websocket.gateway.send.queue.depth` | 로컬 연결·구독·send queue gauge |
| Gateway 전송 | `chat.websocket.gateway.local.deliveries`, `chat.websocket.gateway.outbound.bytes`, `chat.websocket.gateway.batch.frames`, `chat.websocket.gateway.slow_client.disconnects`, `chat.websocket.gateway.write.latency` | 전달량·bytes·frame·slow client·write timer |
| Streams | `chat.redis.stream.append.latency`, `chat.redis.stream.consumer.records`, `chat.redis.stream.worker.batch.latency`, `chat.redis.stream.worker.records`, `chat.redis.stream.dead_letters` | append, new/pending/claim, batch 결과, DLQ |
| Backlog | `chat.redis.stream.group.lag`, `chat.redis.stream.group.pending` | XINFO GROUPS lag / XPENDING count; [상세](streams-lag-metrics.md) |
| Lease | `chat.fanout.owner.lease.acquire`, `chat.fanout.owner.lease.renew`, `chat.fanout.owner.lease.lost`, `chat.fanout.owner.rooms`, `chat.fanout.owner.takeovers`, `chat.fanout.owner.token_mismatch` | 소유권 획득·갱신·상실 및 token 확인 |
| Admission/정책 | `chat.message.admission.rejected`, `chat.message.moderation.rejected` | 제한·Redis 오류·콘텐츠/제재 거부 사유 |
| Gap audit | `chat.room_seq.gap.rooms`, `chat.room_seq.gap.missing_sequences`, `chat.room_seq.gap.max_width`, `chat.room_seq.gap.scanned_rooms` | canonical sequence gap aggregate; 유실 확정 지표 아님 |

구현: [Gateway metrics](../../chat-persistence/src/main/kotlin/service/WebSocketGatewayMetrics.kt), [Stream metrics](../../chat-persistence/src/main/kotlin/service/MessageStreamMetrics.kt), [Lease service](../../chat-persistence/src/main/kotlin/service/FanoutOwnerLeaseService.kt).

## 경보와 release gate

- [Streams 경보](streams-lag-alerts.md): lag/pending warning·critical threshold와 지속 시간을 함께 사용한다.
- [Hot-room gate](../testing/hot-room-release-gate.md): worker batch p95를 end-to-end viewer latency와 혼동하지 않는다. Shard label 수 역시 Redis master 분산의 증거가 아니다.
- [검색 gate](../testing/admin-search-latency.md): warm p95 1초, cold p99 6초를 별도로 계산하고 [slow query plan](../testing/admin-search-plans.md)을 남긴다.
- [Takeover](../testing/fanout-takeover.md): lease counter만으로 장애 복구를 판정하지 않는다. Pending claim, raw arrival, client dedup/render/gap fill을 함께 확인한다. 정상상태의 raw order 검사와 장애 replay의 중복·지연 분류를 구분한다.
- Gap warning은 할당 후 수락 실패로 생긴 hole을 포함할 수 있다. DB·acceptance·DLQ 대조가 필요하다.
- [On-call](on-call.md): warning/critical routing, delivery smoke, PagerDuty 실패의 별도 Slack 통보를 검증한다.

## 아직 필요한 계측

`chat.websocket.ticket.issue.outcomes`, `chat.websocket.ticket.rate_limit.sequential_overcount.suspected`, `chat.websocket.reconnect.attempts`는 정상 reconnect cohort와 순차 counter 소모를 판단하기 위한 제안이다. 현재 등록된 지표라고 전제하지 않는다. [Ticket 정책 기준](../architecture/authentication.md#발급-제한)을 계산할 분모·cohort 정의와 함께 구현해야 한다.

Partition write latency, replica lag의 운영 시계열, 검색 scanned partitions, archive duration 등도 필요에 따라 exporter/앱 계측을 확인한다. 검색 synthetic report가 상시 애플리케이션 metric을 대신하지 않는다. Search projection worker와 해당 lag 지표는 현재 구현으로 나열하지 않는다.

## Cardinality와 로그

Metric label에 messageId·userId·roomId·원본 stream key를 무제한 넣지 않는다. Streams는 consumer group과 shard 중심으로 집계하고 개별 메시지 추적은 제한된 로그/trace로 처리한다. 로그에 세션·티켓·비밀번호·raw IP·message body를 노출하지 않는다. 임계치는 검증 환경 기준선에 맞춰 조정하고 변경 이유를 기록한다.
