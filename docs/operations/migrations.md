# 배포 전환과 데이터 준비

이 문서는 운영자가 적용할 순서다. 문서 정리 과정에서 DB나 운영 환경에 명령을 실행하지 않았다. 기존 데이터·설정과 적용 이력을 먼저 확인하고 백업 및 배포 절차에 따라 수행한다.

## 제재 재시도 테이블

신규 앱 배포 전에 primary PostgreSQL에 다음 정의가 있어야 한다.

1. [sanction-cache-invalidation.sql](../../infra/postgres/sanction-cache-invalidation.sql): 캐시 무효화 durable job.
2. [session-revocation-jobs.sql](../../infra/postgres/session-revocation-jobs.sql): GLOBAL SUSPEND 세션 철회 durable job.

Compose의 신규 DB 초기화와 primary configure에는 정의가 포함되어 있다. 이미 실행 중인 환경에 새 파일을 배치하는 것만으로 적용 완료라고 판단하지 않는다. 테이블과 인덱스 존재, 앱 접근 권한, worker claim 및 실패 후 재시도를 확인한다. Cache job 완료와 session job 완료는 각각 점검한다.

## WebSocket 티켓

부모 세션 digest·issuedAt·expiry를 검증하는 Gateway를 먼저 배포한 뒤 새 티켓을 발급하는 API를 배포한다. 부모 정보가 없는 기존 티켓은 다시 발급받는다. 짧은 TTL을 고려한 reconnect 실패율과 query token fallback 설정을 확인한다. Token/ticket 원문을 로그에 남기지 않는다.

## Stream capacity

Gateway와 writer/fanout의 group 이름을 일치시키고 두 필수 group 생성 상태를 확인한다. ACK-safe admission과 과거 destructive MAXLEN Gateway가 섞여 있으면 이전 코드가 pending entry를 제거할 수 있으므로 함께 전환한다.

용량 제한에서 신규 수락 거부, pending 보존, 모든 group ACK 이후 공간 회수가 예상대로 동작하는지 검증한다. 과거 trim으로 stream에서 사라졌지만 acceptance payload가 남은 경우 primary DB·DLQ·원본 payload를 대조해 개별 복구 방침을 정한다. 확인 없이 orphan key를 일괄 삭제하지 않는다.

## API와 저장소

[Public history cursor 호환](history-cursor-migration.md)은 client 전환 확인 후 종료한다. S3 archive/export 설정은 [인프라 가이드](infrastructure.md)를 따른다. Archive 업로드 성공은 복원 검증 완료와 다르다. RUNNING export 복구에는 staging 파일과 수동 requeue 절차가 필요하다.
