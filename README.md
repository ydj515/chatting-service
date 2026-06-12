# 분산 채팅 시스템

Spring Boot + Redis Pub/Sub + PostgreSQL + Nginx + React로 구성된 분산 채팅 시스템입니다.  
API, WebSocket, Worker, Admin 실행 모듈을 분리해 역할별 수평 확장이 가능하도록 구성합니다.

## 주요 기능

- 회원가입/로그인 및 사용자 조회
- 채팅방 생성/조회/검색/참여/퇴장
- WebSocket 기반 실시간 메시지 전송
- Redis Pub/Sub으로 다중 서버 간 메시지 동기화
- 커서 기반 메시지 페이징
- PostgreSQL streaming read replica 및 파티션 archive
- Admin 전용 실행 모듈과 관리자 API 확장 기반

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Kotlin, Spring Boot 3, Spring Data JPA, Spring WebSocket |
| Infra | PostgreSQL 17, Redis 7, Nginx, Docker Compose |
| Frontend | React 18, TypeScript, Axios, Emotion |

## 빠른 시작

> 사전 요구: [Docker](https://www.docker.com/), [mise](https://mise.jdx.dev/) 설치

```bash
# 1. 인프라 초기화 (기존 볼륨 정리 후 깨끗하게 시작)
mise run clean:infra

# 2. 빌드 + 인프라 + 앱 전체 기동
mise run start:all
```

실행 후 접속:

| 서비스 | URL |
| --- | --- |
| REST API | `http://localhost/api` |
| Health Check | `http://localhost/api/actuator/health` |
| Admin Health Check | `http://localhost/api/admin/health` |
| WebSocket | `ws://localhost/api/ws/chat?userId=1` |
| PostgreSQL Primary | `localhost:5432` |
| PostgreSQL Replica | `localhost:5433` |

종료:
```bash
mise run stop
```

## 클라이언트 실행

```bash
cd client
npm install
npm start
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
client/                        # React 클라이언트
infra/                         # Docker Compose 인프라 설정 (Nginx, Redis, PostgreSQL)
docs/                          # 상세 문서
```

## 문서

| 문서 | 설명 |
| --- | --- |
| [API 레퍼런스](docs/api-reference.md) | REST API 엔드포인트 및 WebSocket 프로토콜 |
| [환경 변수](docs/configuration.md) | Docker/Backend/Client 환경 변수 목록 |
| [인프라 가이드](docs/infrastructure.md) | mise 태스크, PostgreSQL replica/archive, 로드 밸런싱 |
| [API 스펙 (OpenAPI)](docs/openapi.yaml) | Swagger/OpenAPI 3.0 스펙 |
| [고트래픽 설계서](docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md) | 고트래픽 채팅 서비스 설계 문서 |
