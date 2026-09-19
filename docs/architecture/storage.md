# 저장소·검색·내보내기

## Canonical store와 replica

Legacy JPA `messages`와 partitioned canonical `chat_messages` 구현을 설정으로 선택한다. Docker의 partitioned 구성은 createdAt 기준 PostgreSQL partition과 writer의 멱등성 처리를 사용한다. Metadata와 변경은 primary에서 처리한다.

최신 history의 replica 선택은 primary `pg_current_wal_lsn`과 replica replay LSN을 비교한다. 따라잡았으면 idle 상태에서도 lag를 0으로 본다. 뒤처졌으면 replay timestamp를 사용하고, 측정 실패/정보 누락 시 primary로 fallback한다. 측정마다 primary 조회가 추가되며 모든 replica query를 일괄 fallback하는 기능은 아니다.

## Cursor와 관리자 검색

Public history는 roomSeq 경계를 사용한다. Opaque token은 createdAt·roomSeq·messageId를 담지만 client는 해석하지 않고 그대로 돌려준다. Admin room history는 `(roomSeq, createdAt, messageId)` DESC, admin search는 `(createdAt, roomSeq, messageId)` DESC tuple pagination을 사용한다.

Public 숫자 cursor 호환 제거는 client 전환 후 두 release 또는 30일 중 더 긴 기간을 충족해야 한다. 실제 client 전환 완료일은 별도 확인이 필요하다. [Cursor 전환](../operations/history-cursor-migration.md)을 따른다.

검색은 PostgreSQL FTS/trigram과 시간 범위/partition pruning을 우선한다. [검색 gate](../testing/admin-search-latency.md)는 warm p95 1초와 cold p99 6초를 구분한다. [2026-06-14 측정](../performance/admin_search_postgres_tuning_2026-06-14.md)은 해당 환경의 기록이며 현재 운영 SLA 증거가 아니다.

## Export

Worker는 pending export job을 claim하고 chunk별로 CSV를 기록한다. query가 없어도 sender filter를 적용한다. Flush와 `FileChannel.force(true)` 성공 이후에 cursor와 exported rows checkpoint를 저장한다. 완료 산출물은 Object Storage에 업로드하고 안정적인 S3 URI를 보관한다. 조회 시 presigned download URL을 발급하며 실패하면 URL이 없을 수 있다. 로컬 파일 URI를 외부 다운로드 주소로 노출하지 않는다.

재개에는 기존 staging 파일과 job requeue가 필요하다. RUNNING job의 자동 lease 회수를 보장하지 않는다. 파일 append 이후 DB checkpoint 이전 crash는 마지막 chunk 중복을 만들 수 있다. [Atomic manifest 제안](../decisions/export-manifest.md)은 이 창을 해결하기 위한 미구현 설계다.

## Archive와 복구 경계

기본 retention은 100일이다. Archive worker는 오래된 partition을 CSV와 checksum metadata로 복사하고 Object Storage 업로드를 완료한 뒤, 설정에서 허용한 detach/drop을 수행한다. 업로드 실패 시 detach/drop을 진행하지 않는다.

MinIO는 로컬 S3 호환 저장소다. Bucket 접근 제어·TLS·수명 주기·복원 검증은 운영 준비에 포함한다. Archive 생성은 cold query API나 PostgreSQL PITR/재해 복구 절차 구현을 의미하지 않는다. [인프라](../operations/infrastructure.md)와 [운영 준비도](../operations/readiness.md)를 참고한다.
