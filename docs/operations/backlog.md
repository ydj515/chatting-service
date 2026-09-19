# 남은 운영·구조 과제

완료된 ticket Lua, fanout lease, admission Lua, token revocation, cache/session 재시도는 [아키텍처](../architecture/overview.md)에 기록한다. 아래는 미해결 또는 조건부 후속 작업이다. 체크리스트 존재가 구현 승인을 뜻하지 않는다.

| 과제 | 현재 한계 | 완료/전환 조건 |
| --- | --- | --- |
| Export manifest | append 후 DB checkpoint 전 crash에서 chunk 중복 | [제안](../decisions/export-manifest.md)에 따라 crash injection 이후 중복·누락 없이 재개 |
| Export job 복구 | RUNNING 자동 lease reclaim 없음, staging 의존 | worker 교체·crash 후 재개 소유권과 파일 접근 검증 |
| 최신 부하 증거 | 1k 진단 기록만으로 10k 목표 인증 불가 | [단계별 gate](../testing/hot-room-release-gate.md)를 현재 설정에서 통과하고 JSON·환경 보존 |
| 부하 발생기 분리 | sender/viewer 한 프로세스의 event-loop 병목 가능 | sender/viewer 분리 측정 후 서버 병목과 구분; [결정 기록](../performance/phase8_4_1k_gate_sender_fanout_decision_record_2026-06-30.md) 참조 |
| Nginx 재생성 대응 | recreate/scale 이후 stale DNS 가능성 | [라우팅 runbook](../testing/role-routing.md) 통과, 자동 복구 정책 검증 |
| 보안 운영 | 로컬 HTTP/Compose 설정은 운영 TLS·IAM 증거 아님 | TLS/wss, Redis AUTH/TLS, secret 배포·HMAC rotation/refresh 정책 확인 |
| 관리자 인증 | 공유 admin token | 사용자별 감사·권한이 필요한 배포에서 SSO/RBAC 구현·검증 |
| DR/PITR | Archive/chaos가 복원 절차를 대신하지 않음 | PostgreSQL PITR, Redis backup/restore, Object Storage 복원 훈련과 RTO/RPO 기록 |
| Pub/Sub 제어 이벤트 | JOIN 및 force logout 구독 유실 가능 | 필요 시 내구성 있는 이벤트/reconciliation; DB 전달 권한 확인은 유지 |
| 지연 철회 | 재시도 force logout이 새 연결도 닫을 수 있음 | 세션 발급 시각별 연결 종료 정책이 필요하면 protocol 확장·검증 |
| 정책 확장 | substring 금칙어와 기본 제재만 구현 | 구독자 검증, 정규화/도배 규칙은 요구사항과 오탐 기준부터 확정 |
| Ticket UX 계측 | 정상 reconnect cohort·overcount 세부 지표 부족 | [발급 제한 기준](../architecture/authentication.md#발급-제한)을 계산할 분모와 bounded 지표 확보 |
| 과거 acceptance 정합성 | 이전 destructive trim의 orphan 가능성 | DB·DLQ·payload 대조 후 원래 식별자로 복구 |

## 측정 이후 검토할 구조 변경

- OpenSearch: PostgreSQL FTS/trigram·partition pruning 튜닝 후에도 검색 warm p95 1초/cold p99 6초 목표를 반복해서 넘고 query plan이 한계를 입증할 때 검토한다.
- 별도 메시지 저장소: PostgreSQL 쓰기 병목을 실제 측정한 뒤 Scylla/Cassandra 등의 운영 비용과 데이터 모델 전환을 비교한다.
- Hot-room Gateway pool·room-aware routing·lane 분리: [1k 진단 결정](../performance/phase8_4_1k_gate_sender_fanout_decision_record_2026-06-30.md)의 sender/viewer 분리 측정 이후 검토한다. Stream shard 수를 늘려도 한 방의 Redis slot은 분산되지 않는다.
- Kubernetes/Helm/HPA: 배포 대상과 운영 소유권을 확정한 뒤 검토한다. 현재 Compose 문서를 Kubernetes 구현 완료로 해석하지 않는다.
