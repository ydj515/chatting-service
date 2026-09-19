# 문서 안내

현재 동작은 아키텍처, 실행·복구 절차는 운영, 재현 가능한 검증은 테스트 문서에서 관리한다. 과거 구현 계획의 완료 체크리스트는 반복 보관하지 않는다. 미해결 설계는 제안으로, 측정은 날짜와 조건이 있는 기록으로 구분한다.

## 시작하기

| 문서 | 내용 |
| --- | --- |
| [API 레퍼런스](api-reference.md) / [OpenAPI](openapi.yaml) | REST·WebSocket 계약 |
| [환경 설정](configuration.md) | 환경 변수와 기본값 |
| [프론트엔드 규칙](frontend-conventions.md) | 클라이언트 구조와 UI 규칙 |
| [아키텍처 개요](architecture/overview.md) | 실행 역할과 보장 범위 |
| [인프라 실행](operations/infrastructure.md) | 로컬 Gradle·Docker 구성 |

## 설계

- [메시지 수락·Streams·fanout·순서](architecture/messaging.md)
- [인증·티켓·세션 철회](architecture/authentication.md)
- [모더레이션·캐시 무효화](architecture/moderation.md)
- [저장소·검색·export·archive](architecture/storage.md)
- [Redis 키·hash slot](architecture/redis-keys.md)

## 운영

- [배포 전환·스키마 준비](operations/migrations.md), [history cursor 전환](operations/history-cursor-migration.md)
- [관측 지표](operations/observability.md), [Streams lag gauge](operations/streams-lag-metrics.md), [Streams 경보 대응](operations/streams-lag-alerts.md)
- [On-call routing](operations/on-call.md), [PagerDuty 설정](operations/pagerduty.md)
- [운영 준비도](operations/readiness.md), [남은 과제](operations/backlog.md)
- [미구현 제안: export atomic manifest](decisions/export-manifest.md)

## 검증 절차

아래 명령은 저장소 루트에서 실행한다. 부하·chaos·container 조작은 지정된 검증 환경을 대상으로 한다.

- [역할 라우팅](testing/role-routing.md)
- [Fanout takeover](testing/fanout-takeover.md)
- [Reconnect 부하](testing/reconnect-load.md), [chaos 조합](testing/reconnect-chaos.md), [복구 SLO](testing/reconnect-recovery-slo.md)
- [Chaos runbook](testing/chaos.md)
- [Hot-room 단계별 gate](testing/hot-room-release-gate.md)
- [관리자 검색 latency](testing/admin-search-latency.md), [실행 계획 수집](testing/admin-search-plans.md)

## 성능 측정 기록

측정 당시 환경에 대한 기록이다. 현재 코드나 운영 배포의 통과 결과로 재사용하지 않는다. 원본 JSON은 Markdown과 같은 디렉터리에 보존한다.

- [2026-06-14: 1천만 건 관리자 검색](performance/phase5_admin_search_10m_2026-06-14.md)
- [2026-06-14: PostgreSQL 검색 튜닝](performance/admin_search_postgres_tuning_2026-06-14.md)
- [2026-06-30: 1k gate sender/fanout 병목 분리 결정](performance/phase8_4_1k_gate_sender_fanout_decision_record_2026-06-30.md)

## 기존 계획의 통합 위치

| 기존 주제 | 현재 문서 |
| --- | --- |
| 고트래픽 전체 설계, Phase 1~3 auth/ingest/sequence/idempotency | 아키텍처 개요·인증·메시지 |
| Phase 4~5 canonical/search/cursor/export 및 튜닝 | 저장소·cursor 전환·성능 기록 |
| Phase 6 lease/admission, Phase 8 shard/MAXLEN/gap audit | 메시지·Redis 키·관측 지표 |
| Phase 7 routing/reconnect/chaos/search/lag 검증 계획 | testing 문서·Streams 운영 문서 |
| Phase 8 metrics/Cluster/Object Storage/moderation/revocation | 관측·인프라·저장소·모더레이션·인증 |
| Phase 8 단계별 release gate | hot-room gate·1k 결정 기록 |
| 과거 production 평가와 hardening TODO | 운영 준비도·남은 과제·export manifest 제안 |

## 문서 유지 규칙

기능을 변경하면 해당 계약·설정·운영 절차를 함께 갱신한다. 새 문서는 이 목차에 연결하고 현재 구현, 미구현 제안, 검증 결과를 명시한다. 계획 전용 디렉터리나 작업 단계별 중복 명세를 새로 만들지 않는다.

기본값은 코드와 대조하고 운영 승인 여부는 별도로 기록한다. 스크립트 이름의 phase 번호는 기존 실행 인터페이스이므로 문서 이동만으로 바꾸지 않는다. 외부로 전달되는 경보 runbook 경로는 renderer와 생성 YAML을 함께 갱신한다. 파일·이미지·앵커 링크를 검사하고 측정 원본의 수치와 조건을 보존한다.
