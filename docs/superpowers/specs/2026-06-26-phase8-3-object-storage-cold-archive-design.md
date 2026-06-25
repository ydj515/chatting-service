# Phase 8.3 Object Storage + Cold Archive 설계서

- 작성일: 2026-06-26
- 슬라이스: Phase 8.3 Object Storage + cold archive
- 상태: 설계 승인
- 구현 브랜치: `feat/phase8-3-object-storage-cold-archive`
- 계획 문서: `docs/superpowers/plans/2026-06-26-phase8-3-object-storage-cold-archive.md`

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 8의 목표는 Kubernetes 전환 전에 Docker Compose 기준 공개 트래픽 운영 토대를 확보하는 것이다.
- Phase 8.1은 Prometheus/Grafana 관측 파이프라인과 Gateway/Fan-out metric을 완료했다.
- Phase 8.2는 전체 Docker backend의 Redis topology를 3 master + 3 replica Redis Cluster로 전환했다.
- 현재 `AdminMessageExportWorker`는 `java.nio.file.Files`로 worker 로컬 디스크에 CSV를 생성하고 `admin_message_export_jobs.output_uri`에 `file://` URI를 저장한다.
- 로컬 파일 export는 worker instance에 종속되므로 multi-instance, container 재시작, Kubernetes 전환, 관리자 다운로드 URL 제공 요구를 만족하지 못한다.
- 현재 admin export API는 `POST /admin/exports/messages`로 job을 생성할 수 있지만, 완료된 job의 output 위치나 download URL을 조회하는 API가 없다.
- 현재 `infra/postgres/archive/archive-partitions.sh`는 retention 초과 partition을 로컬 `/archive`에 CSV로 복사하고 checksum metadata를 만든 뒤, 설정에 따라 detach/drop한다.
- archive worker는 Object Storage 업로드 없이 DROP까지 수행할 수 있으므로, `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY=true` 환경에서는 retention 초과 데이터가 장기 보관 없이 삭제될 수 있다.
- 현재 Compose에는 S3 호환 Object Storage 서비스가 없고, archive worker가 사용하는 `postgres:17.9-alpine` 이미지에는 S3 업로드 도구가 없다.

### 목표

- Docker Compose에 MinIO 기반 S3 호환 Object Storage를 추가한다.
- 앱 코드에는 S3 호환 `ObjectStoragePort` 추상화 1벌을 추가하고, endpoint/credential/bucket 교체만으로 MinIO와 managed S3를 바꿀 수 있게 한다.
- admin export worker는 실행 중 checkpoint/resume에는 로컬 staging CSV를 계속 사용하고, 완료 시 최종 CSV를 Object Storage에 업로드한다.
- `admin_message_export_jobs.output_uri`에는 만료 URL이 아니라 안정적인 `s3://bucket/key` object URI를 저장한다.
- `GET /admin/exports/{jobId}` API를 추가해 job 상태, object URI, 만료 시간이 있는 download URL을 조회할 수 있게 한다.
- partition archive worker는 CSV와 metadata를 Object Storage cold archive prefix로 업로드한 뒤에만 detach/drop을 허용한다.
- Object Storage 구성과 archive/export 경로를 `.env.example`, `docs/configuration.md`, `docs/infrastructure.md`, OpenAPI에 문서화한다.

### 비범위

- manifest + part file 기반 admin export 원자성 강화는 이번 슬라이스에서 구현하지 않는다. 기존 `docs/export_atomic_manifest_hardening.md`의 Production Hardening 후보로 유지한다.
- export 결과를 Object Storage에서 다시 읽어 이어 쓰는 resume 방식은 이번 슬라이스에서 구현하지 않는다. 진행 중 job의 resume은 기존 로컬 staging file checkpoint를 사용한다.
- Object Storage bucket lifecycle, versioning, object lock, server-side encryption, IAM policy 세분화는 운영 hardening으로 남긴다.
- Kubernetes manifest, Helm chart, ExternalSecret, managed S3 전환은 Phase 9 범위다.
- archive CSV를 Parquet로 변환하거나 cold archive 조회 API를 제공하지 않는다.
- MinIO console을 외부 운영 UI로 공개하지 않는다. Compose에서는 host loopback 또는 내부 네트워크 검증용으로만 둔다.

## 2. 해결 접근

### 선택한 접근

권장안 A를 적용한다.

1. Compose에 `minio`와 bucket init one-shot service를 추가한다.
2. `chat-persistence`에 `ObjectStoragePort`와 S3 호환 adapter를 추가한다.
3. admin export worker는 기존 chunk pagination, CSV escaping, checkpoint cursor를 유지하되 완료 직전에 Object Storage로 업로드한다.
4. DB에는 안정적인 `s3://bucket/key`를 저장하고, admin 조회 API가 요청 시점마다 presigned URL을 새로 발급한다.
5. partition archive worker는 `mc` 또는 동등한 S3 client가 포함된 archive 전용 이미지를 사용해 CSV와 metadata를 cold archive prefix로 업로드한다.
6. archive script는 Object Storage 업로드가 실패하면 해당 partition의 detach/drop을 수행하지 않는다.

### 이유

- 안정적인 object URI와 만료 download URL을 분리하면 URL 만료 이후에도 같은 object에 대해 새 download URL을 발급할 수 있다.
- 기존 local staging checkpoint를 유지하면 Phase 5/6에서 만든 chunk resume 동작을 크게 흔들지 않고 Object Storage 최종 저장을 추가할 수 있다.
- `ObjectStoragePort`는 Kubernetes 전환 시 endpoint, region, credential, bucket만 바꾸면 되므로 Compose 한정 구현이 아니다.
- archive worker의 업로드 도구 문제는 앱 port와 별개다. shell worker는 S3 CLI를 사용하고, 애플리케이션은 port adapter를 사용하는 구조가 각 실행 환경에 더 잘 맞는다.
- DROP 전 업로드 성공을 불변식으로 두면 retention 정책과 장기 보관 요구가 충돌하지 않는다.

## 3. 설계 상세

### Compose Object Storage

- `minio` service는 S3 API endpoint를 Compose 내부 `http://minio:9000`으로 제공한다.
- host port는 기본적으로 `127.0.0.1`에 bind해 로컬 검증 전용으로 둔다.
- MinIO root user/password, bucket 이름, region, endpoint는 환경 변수로 설정한다.
- `minio-init` one-shot service는 앱과 archive worker가 사용하는 bucket을 idempotent하게 생성한다.
- 앱 컨테이너는 shared app environment로 Object Storage endpoint, bucket, region, access key, secret key, presigned URL TTL을 전달받는다.
- archive worker는 같은 bucket과 별도 prefix를 사용한다.

### ObjectStoragePort

`ObjectStoragePort`는 최소 기능만 제공한다.

- object 업로드: local file path, object key, content type을 받아 `s3://bucket/key` 형태의 안정 URI를 반환한다.
- download URL 발급: `s3://bucket/key`와 TTL을 받아 presigned URL을 반환한다.
- adapter는 S3 호환 endpoint override와 path-style access를 지원한다.

Object key naming은 다음처럼 bounded, deterministic prefix를 사용한다.

- admin export: `admin-exports/{jobId}.csv`
- partition archive CSV: `postgres/archive/chat_messages/{partitionName}.csv`
- partition archive metadata: `postgres/archive/chat_messages/{partitionName}.csv.metadata.json`

`roomId`, `userId`, raw query string 같은 민감하거나 cardinality가 큰 값은 object key에 넣지 않는다.

### Admin export flow

1. 관리자가 `POST /admin/exports/messages`로 export job을 생성한다.
2. `admin-export` worker가 pending job을 claim한다.
3. worker는 `CHAT_ADMIN_EXPORT_DIRECTORY` 아래 staging CSV를 만들고, 기존 chunk cursor 방식으로 checkpoint를 갱신한다.
4. chunk export가 끝나면 staging CSV를 `admin-exports/{jobId}.csv` object key로 업로드한다.
5. `markCompleted`는 `output_uri`에 `s3://bucket/admin-exports/{jobId}.csv`를 저장한다.
6. 관리자가 `GET /admin/exports/{jobId}`를 호출하면 API는 job 상태와 함께 완료 job에 대한 presigned `downloadUrl`을 반환한다.

진행 중 checkpoint의 `output_uri`는 local staging `file://` URI를 유지한다. job 완료 후에는 같은 column이 final object URI로 바뀐다. 조회 API는 job status를 기준으로 `RUNNING` 상태의 `file://` URI를 download URL로 공개하지 않는다.

### Admin export 조회 API

`GET /admin/exports/{jobId}`는 다음 응답을 제공한다.

- `jobId`
- `status`
- `createdAt`
- `startedAt`
- `completedAt`
- `exportedRows`
- `outputUri`
- `downloadUrl`
- `downloadUrlExpiresAt`
- `errorMessage`

`downloadUrl`과 `downloadUrlExpiresAt`은 `status = COMPLETED`이고 `outputUri`가 `s3://`일 때만 채운다. 그 외 상태에서는 `null`을 반환한다.

### Partition cold archive flow

1. archive script는 retention 기준보다 오래된 `chat_messages_YYYYMMDD` partition을 찾는다.
2. partition을 local archive directory의 temp CSV로 `\copy`한다.
3. temp CSV를 final CSV로 이동하고 sha256, bytes, row count, archivedAt metadata를 만든다.
4. Object Storage가 활성화되어 있으면 CSV와 metadata JSON을 cold archive prefix로 업로드한다.
5. 업로드가 성공한 경우 metadata에는 object URI와 uploadedAt을 포함한다.
6. `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY=true`인 경우 Object Storage 업로드 성공 뒤에만 detach/drop한다.

Object Storage가 비활성화된 상태에서 `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY=true`를 설정하면 script는 실패해야 한다. 로컬 파일만 생성하고 DROP하는 조합은 Phase 8.3 이후 허용하지 않는다.

### Failure handling

- admin export CSV 작성 실패: 기존처럼 job을 `FAILED`로 전이하고 error message를 저장한다.
- admin export Object Storage 업로드 실패: job을 `FAILED`로 전이하고 staging file은 남겨 재시도/조사 가능하게 한다.
- presigned URL 발급 실패: 조회 API는 서버 오류를 반환하고 job 상태는 변경하지 않는다.
- archive Object Storage 업로드 실패: 해당 partition의 detach/drop을 수행하지 않고 script run을 실패로 끝낸다.
- MinIO bucket init 실패: app과 archive worker는 `minio-init` 성공을 dependency로 가져 race를 줄인다.

### Security / 운영 원칙

- presigned URL TTL 기본값은 짧게 둔다. Compose 기본값은 운영 검증 편의를 위해 환경 변수로 조정 가능해야 한다.
- MinIO credentials는 `.env.example`에 개발용 값만 제공하고 운영에서는 반드시 교체해야 한다.
- MinIO API/console host port는 loopback에만 bind한다.
- object key에는 관리자 token, 사용자 이름, query text 같은 민감 정보를 넣지 않는다.
- nginx는 MinIO를 공개 reverse proxy로 라우팅하지 않는다.

## 4. 구현 계획 요약

세부 implementation plan은 별도 계획 문서에 작성한다. 예상 task 묶음은 다음과 같다.

1. Phase 8.3 spec/plan 문서 작성
2. Object Storage port, S3 adapter, 설정 properties 추가
3. admin export 조회 repository/service/controller/API 추가
4. admin export worker의 final Object Storage upload 전환
5. Compose MinIO와 bucket init 추가
6. archive worker 이미지와 script cold archive 업로드 추가
7. 문서, OpenAPI, client-admin 최소 표시 보강
8. 단위 테스트, script contract test, Compose config, Gradle test 검증

## 5. 복잡도

- admin export CSV 생성 시간 복잡도: `O(E)` (`E`는 export 대상 row 수)
- admin export Object Storage 업로드 시간 복잡도: `O(B)` (`B`는 CSV byte 수)
- admin export 전체 시간 복잡도: `O(E + B)`
- admin export 메모리 복잡도: chunk 조회 기준 `O(C)` (`C`는 export chunk size)
- admin export 로컬 staging 공간 복잡도: `O(B)`
- presigned URL 발급 시간 복잡도: `O(1)`
- partition archive CSV 생성 시간 복잡도: `O(R)` (`R`은 partition row 수)
- partition archive Object Storage 업로드 시간 복잡도: `O(A)` (`A`는 CSV와 metadata byte 수)
- partition archive 전체 시간 복잡도: `O(R + A)`
- partition archive 로컬 공간 복잡도: `O(A)`

## 6. 주의사항

> - presigned URL을 DB에 저장하면 TTL 만료 이후 같은 export를 다시 다운로드할 수 없다. DB에는 안정적인 `s3://` object URI를 저장해야 한다.
> - Object Storage는 파일시스템 rename semantics가 없다. 이번 슬라이스에서는 final CSV 업로드까지만 보장하고, part/manifest 원자성은 별도 hardening으로 분리한다.
> - 진행 중 export의 `output_uri`는 local staging file일 수 있다. admin 조회 API는 `RUNNING` job의 local file URI를 외부에 공개하면 안 된다.
> - cold archive upload가 실패했는데 partition을 detach/drop하면 retention 초과 메시지가 장기 보관 없이 삭제된다. DROP은 upload 성공 뒤에만 허용해야 한다.
> - MinIO는 Compose 검증용 S3 호환 저장소다. 운영에서는 credential, lifecycle, encryption, backup, bucket policy를 별도로 확정해야 한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 안정 `s3://` URI 저장 + 조회 시 presigned URL 발급 | URL 만료 후에도 재발급 가능하고 async job 조회 모델과 맞다 | 조회 API와 repository 확장이 필요하다 | 선택 |
| 완료 시 presigned URL을 `output_uri`에 저장 | 구현 변경량이 작다 | URL 만료 후 복구가 어렵고 object identity가 DB에 남지 않는다 | 제외 |
| manifest + part file export 동시 도입 | retry/resume/idempotency가 가장 강하다 | Phase 8.3 범위가 커지고 Object Storage 원자성 검증 비용이 크다 | 후속 hardening |
| archive script도 앱 `ObjectStoragePort`를 호출 | 업로드 로직을 한 곳에 모을 수 있다 | shell worker가 앱 runtime/API에 의존해 운영 경계가 흐려진다 | 제외 |
| archive worker는 로컬 파일만 유지하고 DROP 금지 | 데이터 삭제 위험은 줄어든다 | Phase 8.3의 cold archive 목표와 K8s 전환성을 만족하지 못한다 | 제외 |

## 8. 후속 질문

- Phase 8.3 이후 admin export manifest/part file hardening을 별도 Phase로 승격할 것인가?
- Object Storage bucket lifecycle과 retention policy를 Phase 8 운영 hardening에 포함할 것인가?
- cold archive된 partition을 관리자 검색/복구 대상으로 조회하는 API를 장기 로드맵에 둘 것인가?
