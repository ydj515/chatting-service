# 운영 준비도

확인 기준: 현재 저장소의 코드·설정과 보존된 측정 기록. 이 문서는 운영 배포 승인이나 최신 실환경 검증 결과가 아니다.

## 구현과 운영 증거 구분

| 영역 | 저장소에 있는 것 | 운영 전에 남은 확인 |
| --- | --- | --- |
| Redis HA | Compose 3 master + 3 replica, AOF | 실제 장애 영역 분산, failover 손실 창, backup/restore |
| 메시지 신뢰성 | 원자 acceptance, ACK-safe capacity, pending/DLQ, owner lease | 포화·장애·재처리 시나리오와 중복/유실 대조 |
| 인증·제재 | 일회용 부모 연결 티켓, token cutoff, durable retry | 테이블 선적용, Redis 장애 후 복구, Gateway 구독 장애 |
| 저장소 | Primary/replica, partition, S3 export/archive | Replica fallback 부하, export crash 복구, restore/PITR |
| 관측 | Prometheus/Grafana, Streams/Gateway 계측, on-call 설정 | 실제 notification 전달, 보안 설정, threshold 보정 |
| 부하 | 단계별 gate와 load/reconnect/chaos script | 현재 배포 구성의 1k→10k 단계별 증거 |

## Release 증거

- [역할 라우팅](../testing/role-routing.md): recreate/scale 후 API·WS·Admin이 올바른 upstream으로 간다.
- [Hot-room gate](../testing/hot-room-release-gate.md): accepted/received 비율, 송신 초과 시간, shard·batch latency·lag를 함께 검사한다.
- [Takeover](../testing/fanout-takeover.md)와 [reconnect recovery](../testing/reconnect-recovery-slo.md): raw arrival과 client dedup/render/gap fill 결과를 구분한다.
- [검색 gate](../testing/admin-search-latency.md): warm p95 ≤ 1초, cold p99 ≤ 6초 및 실패 응답 0건을 별도로 기록한다.
- [경보](streams-lag-alerts.md)와 [on-call](on-call.md): rule 존재, alert firing, notification 수신을 각각 검증한다.
- [마이그레이션](migrations.md): 스키마 준비와 혼합 버전 배포 위험을 확인한다.

2026-06-14 검색 측정과 2026-06-30 1k gate 분석은 과거 환경의 증거다. 이후 코드 수정으로 해당 결과가 자동 갱신되지 않는다. 특히 10k viewer/10k msg/sec 달성과 production readiness를 이 기록만으로 선언하지 않는다.

## 남은 운영 판단

[후속 과제](backlog.md)의 보안 경계, DR, export crash 중복, 부하 발생기 한계를 관리한다. Kubernetes 도입 자체를 성능·신뢰성 증거로 보지 않는다. 먼저 배포 대상 환경에서 SLO와 복구 증거를 확보한다.
