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

PagerDuty key는 [PagerDuty Events API v2 Integration Key 발급 절차](docs/pagerduty_events_api_v2_integration_key.md)를 따라 발급합니다. 현재 Alertmanager 설정은 `pagerduty_configs.routing_key_file`을 사용하므로 `Events API v2` integration type의 `Integration Key`가 필요합니다.

기본 sample secret은 기동용 placeholder일 뿐 실제 알림을 보내지 않습니다. 실제 운영 secret 파일을 다른 경로에 둘 경우에도 같은 환경변수로 경로를 지정합니다.

PagerDuty notification 실패는 Alertmanager 자체 metric 기반의 `AlertmanagerPagerDutyNotificationFailures` warning alert로 감지하며, PagerDuty가 아닌 Slack으로 전송합니다. 자세한 운영 절차는 [Alertmanager On-call Wiring](docs/alertmanager_oncall_wiring.md)을 참고합니다.

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

Phase 8.4 hot room shard 분산 release gate:

```bash
CHAT_PROMETHEUS_URL=http://localhost:9090 node scripts/phase8-hot-room-release-gate.mjs
```

10,000 viewer gate를 실행할 때는 backend 시작 전 `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP`를 viewer 수 이상으로 설정해야 합니다.

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
chat-domain/                   # 도메인/DTO/서비스 인터페이스
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
| [인프라 가이드](docs/infrastructure.md)                                                 | mise 태스크, PostgreSQL replica/archive, 로드 밸런싱 |
| [API 스펙 (OpenAPI)](docs/openapi.yaml)                                             | Swagger/OpenAPI 3.0 스펙                       |
| [고트래픽 설계서](docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md) | 고트래픽 채팅 서비스 설계 문서                            |
