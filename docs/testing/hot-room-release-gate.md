# Hot-room 단계별 release gate

[실행 스크립트](../../scripts/phase8-hot-room-release-gate.mjs)는 단계별 load summary와 Prometheus snapshot을 검사한다. 첫 실패에서 중단하며 성공한 앞 단계와 실패 원인을 JSON으로 출력한다. 실패 exit code는 1이다.

## 기본 조건

| 항목 | 기본값 |
| --- | --- |
| 단계별 viewers / messages per second | 1,000 → 3,000 → 5,000 → 7,000 → 10,000 |
| 단계당 duration | 60초 |
| sender 수 | 16 |
| 최소 accepted ratio | 0.99 |
| 최소 received ratio | 0.90 |
| 최대 send overrun | 1,000ms |
| 최소 관측 stream shard 수 | 16 |
| Fanout worker batch p95 | 500ms 이하 |
| 최대 stream group lag | 1,000 entries |

설정 파싱은 [plan 모듈](../../scripts/lib/phase8HotRoomReleaseGatePlan.mjs), 실행과 중단은 [runner](../../scripts/lib/phase8HotRoomReleaseGateRunner.mjs)를 따른다. 위 값은 gate 기준이며 측정 결과가 아니다.

## 실행

아래 부하는 검증 환경에서만 실행한다. 방·인증·Gateway/Redis 설정과 ticket 발급 한도, 파일 descriptor, proxy 연결 예산을 먼저 준비한다. 명령은 저장소 루트 기준이다.

```bash
node scripts/phase8-hot-room-release-gate.mjs --room 1 --prometheus-url http://localhost:9090
node scripts/phase8-hot-room-release-gate.mjs --single-stage --room 1 --viewers 1000 --messages-per-sec 1000
```

`--stages`로 단계 집합을 바꾸거나 `--single-stage`와 `--viewers`/`--messages-per-sec`로 단일 단계를 실행한다. 두 모드를 혼합하지 않는다. Duration, senders, accepted/received ratio, overrun, 최소 shard, 최대 fanout p95/lag는 별도 옵션으로 조정할 수 있다. 통과를 위해 임계치를 바꿨다면 변경 이유와 실제 값을 결과에 함께 보존한다.

## Prometheus 해석

```promql
histogram_quantile(0.95, sum(rate(chat_redis_stream_worker_batch_latency_seconds_bucket{worker_role="fanout",outcome="success"}[1m])) by (le))
count(count by (stream_shard) (chat_redis_stream_group_lag{consumer_group="fanout",stream_shard!="unknown"}))
max(chat_redis_stream_group_lag{stream_shard!="unknown"})
```

첫 값은 worker batch latency이며 viewer가 화면에 표시할 때까지의 end-to-end latency가 아니다. Shard count는 관측 label 수이고 방별 분산이나 Redis master 분산을 입증하지 않는다. 격리된 부하 환경과 scrape 신선도를 확인한다.

Viewer 준비·acceptance·실제 수신·송신 overrun을 함께 본다. 한 프로세스의 sender/viewer event-loop 포화는 서버 한계와 구별해야 한다. [2026-06-30 결정 기록](../performance/phase8_4_1k_gate_sender_fanout_decision_record_2026-06-30.md)에 당시 1k 결과와 분리 측정 순서가 있다.

JSON report와 커밋, JVM/Node, Gateway/worker 수, room shard 설정, Redis topology, ticket limit, 호스트 자원을 함께 기록한다. 토큰·티켓·비밀번호는 산출물에 포함하지 않는다.
