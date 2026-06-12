# 환경 변수

루트의 `.env.example`과 `client/.env.example`에 기본값이 정리되어 있습니다.  
필요하면 `.env.example`을 복사해 `.env`를 만든 뒤 값을 조정하세요.

## Docker / Backend

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `TZ` | `Asia/Seoul` | 컨테이너 OS 시간대 |
| `APP_TIME_ZONE` | `Asia/Seoul` | Spring Jackson 및 Hibernate JDBC 시간대 |
| `DB_TIMEZONE` | `Asia/Seoul` | PostgreSQL 세션/서버 시간대 |
| `SERVER_PORT` | `8080` | Spring Boot 내부 포트 |
| `SERVER_CONTEXT_PATH` | `/api` | API context path |
| `NGINX_HTTP_PORT` | `80` | 호스트에 노출할 Nginx 포트 |
| `DB_NAME` | `chatdb` | PostgreSQL DB 이름 |
| `DB_USERNAME` | `chatuser` | PostgreSQL 사용자 |
| `DB_PASSWORD` | `chatpass` | PostgreSQL 비밀번호 |
| `DB_REPLICA_HOST_PORT` | `5433` | 호스트에 노출할 PostgreSQL read replica 포트 |
| `POSTGRES_REPLICATION_USER` | `chat_replicator` | PostgreSQL streaming replication 사용자 |
| `POSTGRES_REPLICATION_PASSWORD` | `replicatorpass` | PostgreSQL streaming replication 비밀번호 |
| `CHAT_MESSAGE_RETENTION_DAYS` | `100` | 메시지 파티션 보관 기간 |
| `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY` | `false` | archive 성공 후 partition detach/drop 여부 |
| `CHAT_PARTITION_ARCHIVE_INTERVAL_SECONDS` | `86400` | archive worker 실행 주기 |
| `REDIS_PORT` | `6379` | Redis 내부 포트 |
| `CHAT_API_CORS_ALLOWED_ORIGINS` | `*` | REST API CORS 허용 origin |
| `CHAT_WEBSOCKET_ALLOWED_ORIGINS` | `*` | WebSocket 허용 origin |
| `CHAT_MESSAGE_SEQUENCE_TTL` | `24h` | Redis 메시지 시퀀스 키 TTL |

## Client

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VITE_CHAT_API_BASE_URL` | `/api` | Axios base URL |
| `VITE_CHAT_WS_BASE_URL` | `/api/ws/chat` | WebSocket base URL |
| `VITE_CHAT_API_TIMEOUT_MS` | `30000` | API 요청 timeout |
| `VITE_CHAT_WS_MAX_RECONNECT_ATTEMPTS` | `5` | WebSocket 최대 재연결 횟수 |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:80` | Vite 개발 서버 proxy target |
