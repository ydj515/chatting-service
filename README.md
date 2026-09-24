# 분산 채팅 시스템

Spring Boot + Redis Pub/Sub + PostgreSQL + Nginx + React로 구성된 분산 채팅 시스템입니다.  
API, WebSocket, Worker, Admin 실행 모듈을 분리해 역할별 수평 확장이 가능하도록 구성합니다.

## 주요 기능

- 회원가입/로그인 및 사용자 조회
- 채팅방 생성/조회/검색/참여/퇴장
- WebSocket 기반 실시간 메시지 전송
- Redis Pub/Sub으로 다중 서버 간 메시지 동기화
- 커서 기반 메시지 페이징
- PostgreSQL streaming read replica 및 Object Storage 기반 파티션 cold archive
- S3 호환 Object Storage 기반 admin export 산출물 저장과 presigned download URL
- Admin 전용 실행 모듈과 관리자 API 확장 기반

## 기술 스택

| 영역       | 기술                                                       |
|----------|----------------------------------------------------------|
| Backend  | Kotlin, Spring Boot 3, Spring Data JPA, Spring WebSocket |
| Infra    | PostgreSQL 17, Redis 7, MinIO, Nginx, Docker Compose     |
| Frontend | React 18, TypeScript, Axios, Emotion                     |

## 빠른 시작

> 사전 요구: [Docker](https://www.docker.com/), [mise](https://mise.jdx.dev/) 설치

```bash
# 최초 1회: 개발 도구(JDK 21 등) 및 클라이언트 의존성 설치
mise run setup
```

### 로컬 개발 (기본, 권장)

인프라와 nginx 게이트웨이(`:80`)만 Docker로 띄우고, 백엔드 4종은 호스트에서 Gradle(`bootRun`)로 실행합니다. 이미지 빌드가 없어 코드 변경이 바로 반영되고, `/api` 라우팅은 프로덕션과 동일하게 nginx가 담당합니다(`/api/ws`→websocket, `/api/admin`→admin, `/api`→api).

```bash
mise run            # 인프라 + nginx(:80) + 백엔드 4종(gradle) + 클라이언트 한 번에
                    # (= mise run dev)

mise run dev:api    # 작업 중인 앱 하나만 (인프라 자동 기동). websocket/admin/worker 동일
```

| 서비스                                        | URL                                          |
|--------------------------------------------|----------------------------------------------|
| API 게이트웨이                                  | `http://localhost/api` (nginx-dev → 호스트 백엔드) |
| 사용자 클라이언트                                  | `http://localhost:5173`                      |
| 관리자 클라이언트                                  | `http://localhost:5174`                      |
| chat-api / websocket / admin / worker (직접) | `localhost:8080` / `8081` / `8082` / `8083`  |

> nginx 게이트웨이(`host.docker.internal`로 호스트 백엔드 라우팅)는 macOS/Windows Docker Desktop 기준입니다.

### 전체 도커 클러스터 (nginx·멀티 인스턴스, 통합 검증)

이미지를 빌드해 nginx 게이트웨이와 다중 인스턴스를 포함한 전체 클러스터를 기동합니다.

Alertmanager는 fresh checkout에서도 cluster가 기동되도록 기본값으로 tracked sample secret을 읽습니다. 실제 Slack/PagerDuty delivery를 확인하려면 로컬 secret 파일을 생성하고, 해당 파일 경로를 `ALERTMANAGER_SLACK_WEBHOOK_URL_FILE`, `ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE`로 지정해야 합니다. 실제 값 파일은 `.gitignore` 처리되어 Git에 올라가지 않습니다.

PagerDuty 호출은 `ALERTMANAGER_PAGERDUTY_ENABLED`로 토글합니다. 기본값은 `true`라서 현재처럼 `critical` 또는 `release_blocking="true"` alert가 PagerDuty로 전달됩니다. `false`로 설정하면 PagerDuty 호출을 하지 않고 같은 alert를 Slack receiver로 fallback 전송합니다.

```bash
cp infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample \
  infra/alertmanager/secrets/alertmanager_slack_webhook_url
cp infra/alertmanager/secrets/alertmanager_pagerduty_routing_key_sample \
  infra/alertmanager/secrets/alertmanager_pagerduty_routing_key
```

설정해야 하는 값:

| 파일 | 설정 값 |
| --- | --- |
| `infra/alertmanager/secrets/alertmanager_slack_webhook_url` | Slack-compatible webhook URL |
| `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key` | PagerDuty Events API v2 Integration Key |

PagerDuty key는 [PagerDuty Events API v2 Integration Key 발급 절차](docs/operations/pagerduty.md)를 따라 발급합니다. 현재 Alertmanager 설정은 `pagerduty_configs.routing_key_file`을 사용하므로 `Events API v2` integration type의 `Integration Key`가 필요합니다.

기본 sample secret은 기동용 placeholder일 뿐 실제 알림을 보내지 않습니다. 실제 운영 secret 파일을 다른 경로에 둘 경우에도 같은 환경변수로 경로를 지정합니다.

PagerDuty notification 실패는 Alertmanager 자체 metric 기반의 `AlertmanagerPagerDutyNotificationFailures` warning alert로 감지하며, PagerDuty가 아닌 Slack으로 전송합니다. 자세한 운영 절차는 [Alertmanager On-call Wiring](docs/operations/on-call.md)을 참고합니다.

```bash
mise run clean:infra   # (선택) 기존 볼륨 정리 후 깨끗하게 시작
mise run start         # 이미지 빌드 + 전체 클러스터 + 클라이언트 기동
```

| 서비스                | URL                                    |
|--------------------|----------------------------------------|
| REST API           | `http://localhost/api`                 |
| Health Check       | `http://localhost/api/actuator/health` |
| Admin Health Check | `http://localhost/api/admin/health`    |
| WebSocket          | `ws://localhost/api/ws/chat?userId=1`  |
| PostgreSQL Primary | `localhost:5432`                       |
| PostgreSQL Replica | `localhost:5433`                       |
| MinIO S3 API       | `http://localhost:9000`                |
| MinIO Console      | `http://localhost:9001`                |
| Prometheus         | `http://localhost:9090`                |
| Alertmanager       | `http://localhost:9093`                |

Phase 8.4 hot room shard 분산 staged release gate:

```bash
CHAT_PROMETHEUS_URL=http://localhost:9090 node scripts/phase8-hot-room-release-gate.mjs
```

기본 gate는 `1k -> 3k -> 5k -> 7k -> 10k` 순서로 실행하며, 어느 단계까지 통과했는지 JSON으로 출력합니다. 병목 구간을 다르게 좁히려면 `--stages 1000,2000,4000,8000,10000`처럼 stage 목록을 지정합니다. 기존 10k 단일 실행은 `--single-stage --viewers 10000 --messages-per-sec 10000`로 실행합니다.

10,000 viewer stage를 실행할 때는 backend 시작 전 `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP`를 최대 viewer 수 이상으로 설정해야 합니다. nginx staged gate 예산은 `NGINX_WORKER_PROCESSES`, `NGINX_WORKER_CONNECTIONS`, `NGINX_WORKER_RLIMIT_NOFILE`, `NGINX_NOFILE_SOFT`, `NGINX_NOFILE_HARD`로 조정합니다.

Phase 8.5 moderation smoke:

```bash
mise run verify:moderation
```

종료:
```bash
mise run stop
```

## 프로젝트 구조

```
chat-application/              # 통합 실행 fallback / 로컬 bootstrap
chat-api/                      # 사용자 REST API 기능 모듈
chat-admin/                    # 관리자 API 기능 모듈
chat-domain/                   # JPA 통합 업무 모델과 예외
chat-core/                     # 사용자·채팅·관리자 유스케이스/공유 계약/입출력 타입/저장소 포트
chat-persistence/              # JPA/Redis/서비스 구현
chat-websocket/                # WebSocket 핸들러/설정
chat-api-application/          # API 실행 모듈
chat-websocket-application/    # WebSocket Gateway 실행 모듈
chat-worker-application/       # Worker 실행 모듈
chat-admin-application/        # Admin 실행 모듈
client/                        # 사용자 React 클라이언트
client-admin/                  # 관리자 React 클라이언트
infra/                         # Docker Compose 인프라 설정 (Nginx, Redis, PostgreSQL)
docs/                          # 상세 문서
```

## 문서

| 문서                                                                                | 설명                                           |
|-----------------------------------------------------------------------------------|----------------------------------------------|
| [API 레퍼런스](docs/api-reference.md)                                                 | REST API 엔드포인트 및 WebSocket 프로토콜              |
| [환경 변수](docs/configuration.md)                                                    | Docker/Backend/Client 환경 변수 목록               |
| [인프라 가이드](docs/operations/infrastructure.md)                                                 | mise 태스크, PostgreSQL replica/archive, 로드 밸런싱 |
| [API 스펙 (OpenAPI)](docs/openapi.yaml)                                             | Swagger/OpenAPI 3.0 스펙                       |
| [문서 안내](docs/README.md) | 설계·운영·검증·후속 과제 목차                            |

## 메시지 재전송과 수락 결과 보관

메시지 수락은 `(roomId, senderId, clientMessageId)`를 기준으로 Redis Lua에서 중복 확인과
Streams 추가를 함께 처리합니다. 같은 키의 재전송에는 최초 메시지 ID, 내용, 순번과 생성 시각을 반환합니다.
중복 키와 스트림은 같은 room hash tag를 사용하므로 Redis Cluster에서도 같은 슬롯에서 처리합니다.
동시 요청이 순번을 먼저 발급받은 경우 사용하지 않은 순번이 남을 수 있습니다.

처리 중인 수락 결과는 만료시키지 않습니다. writer가 DB 저장 또는 DLQ 격리 성공을 확인한 뒤
ACK 전에 24시간 TTL을 설정하고, 이후 재전송은 primary DB의 기존 메시지 조회로 처리합니다.
장기 pending 메시지의 수락 키는 유지되므로 backlog와 Redis 메모리를 함께 관리해야 합니다.
보장은 Redis의 기존 내구성·보존 정책을 전제로 합니다.

전환 시 writer를 먼저 배포하고, 기존 gateway의 신규 수락을 중단한 뒤 기존 Streams backlog를
DB에 반영하고 모든 gateway를 교체합니다. 이전 gateway는 새 중복 키를 만들지 않으므로
혼합 실행 또는 기존 backlog가 남은 상태에서는 새 수락 경로만으로 중복 방지를 보장할 수 없습니다.

## 조회 권한과 장애 처리

방 상세·멤버 목록은 인증된 활성 멤버에게만 제공하고 매번 DB에서 멤버십을 확인합니다.
검색 결과는 방 메타데이터만 제공하며 메시지 내용을 포함하지 않습니다.

역직렬화할 수 없는 Streams 레코드는 `rawFields`에 원문 필드를 담아 consumer group의 DLQ로
격리한 뒤 ACK합니다. DLQ 저장 실패 시 원본은 pending에 남고 정상 레코드는 계속 처리합니다.
ACK 실패 후 재시도에서는 DLQ가 중복될 수 있으므로 `sourceStreamKey + sourceRecordId + consumerGroup`으로 식별합니다.

read replica의 지연 0 판단은 Primary에서 읽은 WAL 위치를 replica가 재생했는지 확인합니다.
Primary 위치 또는 replica 재생 시각을 확인할 수 없으면 최신 메시지 조회는 Primary를 사용합니다.
지연 측정마다 Primary WAL 조회가 한 번 추가됩니다.

WebSocket 티켓은 세션 지문·발급 시각·만료를 저장하고 소비 시 개별/사용자 전체 철회를 확인합니다.
배포 시 모든 ticket 소비 gateway를 먼저 교체한 뒤 ticket 발급 API를 교체합니다.
세션 연결 정보가 없는 구버전 티켓은 거부되므로 전환 중에는 새 티켓으로 재접속해야 합니다.
모든 소비 gateway가 교체되기 전에는 기존 gateway가 철회 검사를 생략할 수 있습니다.

## Kotlin 정적 품질 검사

참고 프로젝트의 Detekt + ktlint 구성을 전체 Kotlin 모듈에 적용합니다.
Kotlin 2.0.21과 호환되는 Detekt 1.23.8, ktlint Gradle 플러그인 13.1.0,
ktlint 엔진 1.5.0을 버전 카탈로그에서 관리합니다.
Java 소스가 없으므로 스타일 검사는 ktlint가 담당합니다.
줄 길이 제한과 대입문·인자 목록·함수 선언·메서드 체인 등의 강제 줄바꿈은 비활성화합니다.
직접 작성한 줄바꿈은 유지하고, 들여쓰기·공백·import 등의 기본 스타일만 자동 정리합니다.

```bash
./gradlew verifyKotlinQuality  # Detekt + ktlint, 테스트/외부 인프라 불필요
./gradlew verifyDetekt        # main/test Kotlin 타입 해석 기반 결함·복잡도 검사
./gradlew verifyKotlinFormat  # Kotlin 소스 및 Gradle Kotlin DSL 스타일 검사
./gradlew formatKotlin        # 전체 Kotlin 소스 및 빌드 스크립트 자동 포맷 (파일 변경)
./gradlew check               # 모든 모듈의 테스트 및 정적 검사
```

`check`와 PR의 `Kotlin Quality` workflow에서 위반 시 빌드를 실패시킵니다.
`./gradlew check`는 전체 모듈 테스트, `:chat-application:architectureTest`, Kover 검증까지 실행합니다.
ArchUnit은 실제 운영 클래스를 대상으로 모듈 의존 방향, 컨트롤러의 저장소 직접 접근,
트랜잭션 선언 위치를 검사합니다. 현재 domain의 JPA 엔티티·Spring Data 감사 어노테이션은
기존 통합 모델에 따라 허용하며, 도메인과 영속 모델을 분리할 때 프레임워크 독립 규칙을 추가합니다.

Kover는 전체 모듈을 집계하고 운영 패키지를 제외하지 않습니다. 최초 측정값(라인 78.17%, 분기 60.85%)을
기준으로 최소 라인 78%, 분기 60%를 적용합니다. `./gradlew koverHtmlReport koverXmlReport koverVerify`로
별도 실행할 수 있고 보고서는 `build/reports/kover/`에 생성됩니다. CI는 정적 분석과 전체 검증을 모두 실행합니다.

설정은 `.editorconfig`, `config/detekt/detekt.yml`에서 관리하며,
리포트는 각 모듈의 `build/reports/detekt`, `build/reports/ktlint`에 생성합니다.
루트 빌드 스크립트의 ktlint 리포트는 루트 `build/reports/ktlint`에 생성합니다.

초기 baseline 정리 후 ktlint 위반은 0건이며, 모든 ktlint baseline은 비어 있습니다.
Detekt baseline은 286건에서 72건, 이번 정리에서 48건으로 줄였습니다.
유지한 항목의 사유와 재검토 조건은 [baseline 검토 기록](config/detekt/baseline-review.md)에 정리했습니다.
기록되지 않은 위반은 빌드를 실패시킵니다. ktlint baseline은 파일·규칙 단위로 위반을
숨길 수 있으므로 새 예외를 일괄 추가하지 않습니다.

`verifyDetekt`는 모든 모듈의 `detektMain`, `detektTest`를 실행해 타입 정보까지 검사합니다.
컴파일 및 의존성 해석은 필요하지만 테스트를 실행하거나 외부 인프라에 접속하지 않습니다.
모듈별 `check`에도 타입 해석 검사를 연결했습니다. 기본 `detekt` 검사도 유지합니다.

`config/detekt/packages/*.yml`은 기존 소스 경로에서 생략한 모듈 패키지 접두사만 정의합니다.
Mockito 매처·캡처 호출의 반환값 무시와 `lateinit` 주입 필드는 도구 설정에서 허용하며,
그 밖의 반환값 무시나 재할당 가능한 변수 검사는 유지합니다.
Redis/JDBC의 nullable Java API 대응은 해당 함수의 사유가 있는 제한적 suppression으로 유지합니다.

Baseline은 CI에서 생성하지 않습니다. 기존 항목을 수정하면 해당 XML 항목도 제거합니다.
재생성이 필요한 경우 기본 검사와 타입 해석 검사의 결과를 함께 검토해야 합니다.
아래 명령은 source set별 baseline도 생성하므로, 결과를 검토해 모듈 baseline에 통합하고
중복된 source set 파일이 별도 예외로 남지 않도록 정리해야 합니다.

```bash
./gradlew detektBaseline detektBaselineMain detektBaselineTest
```

### Stream admission and acceptance retention

Stream capacity now rejects new messages instead of trimming unread or pending entries.
At capacity, only the prefix acknowledged by every existing consumer group is reclaimed,
and the configured writer and fanout groups must both exist. HTTP returns 429; WebSocket
returns MESSAGE_ADMISSION_REJECTED. Retry with the same clientMessageId after workers catch up.
Gateway and worker consumer-group names must match. Deploy all gateways together; old gateways
can still trim pending entries. Existing orphaned acceptance keys from earlier trimming require
operator reconciliation against the primary database and retained payloads before rollout.
Pending acceptance payloads remain durable; DB persistence or successful writer DLQ quarantine
starts a 24-hour retention period. After DLQ retention expires, retrying the client ID can create
a new submission; DLQ replay must preserve the original message identity.
