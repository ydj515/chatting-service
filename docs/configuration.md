# 환경 변수

루트의 `.env.example`과 `client/.env.example`에 기본값이 정리되어 있습니다.  
필요하면 `.env.example`을 복사해 `.env`를 만든 뒤 값을 조정하세요.

## Docker / Backend

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `TZ` | `Asia/Seoul` | 컨테이너 OS 시간대 |
| `APP_TIME_ZONE` | `Asia/Seoul` | Spring Jackson 및 Hibernate JDBC 시간대 |
| `DB_TIMEZONE` | `Asia/Seoul` | PostgreSQL 세션/서버 시간대 |
| `SERVER_PORT` | `8080` | Spring Boot 내부 포트. Nginx upstream도 이 값을 사용 |
| `SERVER_CONTEXT_PATH` | `/api` | API context path |
| `NGINX_HTTP_PORT` | `80` | 호스트에 노출할 Nginx 포트 |
| `CHAT_API_IMAGE` | `chatting-service-chat-api-application:local` | API role Compose 서비스가 공유할 이미지 태그 |
| `CHAT_WEBSOCKET_IMAGE` | `chatting-service-chat-websocket-application:local` | WebSocket role Compose 서비스가 공유할 이미지 태그 |
| `CHAT_WORKER_IMAGE` | `chatting-service-chat-worker-application:local` | Worker role Compose 서비스 이미지 태그 |
| `CHAT_ADMIN_IMAGE` | `chatting-service-chat-admin-application:local` | Admin role Compose 서비스 이미지 태그 |
| `DB_NAME` | `chatdb` | PostgreSQL DB 이름 |
| `DB_USERNAME` | `chatuser` | PostgreSQL 사용자 |
| `DB_PASSWORD` | `chatpass` | PostgreSQL 비밀번호 |
| `DB_REPLICA_HOST_PORT` | `5433` | 호스트에 노출할 PostgreSQL read replica 포트 |
| `POSTGRES_REPLICATION_USER` | `chat_replicator` | PostgreSQL streaming replication 사용자 |
| `POSTGRES_REPLICATION_PASSWORD` | `replicatorpass` | PostgreSQL streaming replication 비밀번호 |
| `CHAT_MESSAGE_RETENTION_DAYS` | `100` | 메시지 파티션 보관 기간 |
| `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY` | `false` | archive 성공 후 partition detach/drop 여부 |
| `CHAT_PARTITION_ARCHIVE_INTERVAL_SECONDS` | `86400` | archive worker 실행 주기 |
| `WORKER_ROLES` | `message-writer,fanout,search-projection,archive` | `chat-worker-application`에서 활성화할 worker role 목록 |
| `CHAT_WORKER_POLL_DELAY_MILLIS` | `100` | worker scheduler poll 간격 |
| `CHAT_WORKER_WRITER_CONSUMER_GROUP` | `message-writer` | Redis Streams writer consumer group 이름 |
| `CHAT_WORKER_WRITER_READ_COUNT` | `100` | writer worker가 poll 1회에 읽을 최대 stream record 수 |
| `CHAT_WORKER_FANOUT_CONSUMER_GROUP` | `fanout` | Redis Streams fanout consumer group 이름 |
| `CHAT_WORKER_FANOUT_READ_COUNT` | `100` | fanout worker가 poll 1회에 읽을 최대 stream record 수 |
| `REDIS_PORT` | `6379` | Redis 내부 포트 |
| `CHAT_REDIS_MEMBERSHIP_TOPIC` | `chat.membership` | REST create/join 결과를 WebSocket 노드에 전파하는 Redis 제어 topic |
| `CHAT_REDIS_STREAMS_ROOM_STREAM_KEY_PREFIX` | `chat:stream:room:` | 방별 Redis Streams key prefix |
| `CHAT_REDIS_STREAMS_KNOWN_STREAMS_KEY` | `chat:stream:rooms` | worker가 poll할 stream key index set |
| `CHAT_REDIS_STREAMS_SHARD_COUNT` | `1` | room stream shard 개수 |
| `CHAT_AUTH_SESSION_SECRET` | `local-development-session-secret-change-me` | 로그인 session token HMAC 서명 secret. 운영 환경에서는 반드시 교체 |
| `CHAT_AUTH_SESSION_TTL` | `12h` | 로그인 session token TTL |
| `CHAT_AUTH_SESSION_TOKEN_QUERY_PARAM` | `token` | local/dev 호환 모드에서만 허용할 legacy session token query parameter |
| `CHAT_AUTH_WEB_SOCKET_TICKET_TTL` | `30s` | WebSocket one-time ticket TTL |
| `CHAT_AUTH_WEB_SOCKET_TICKET_QUERY_PARAM` | `ticket` | WebSocket handshake ticket query parameter |
| `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_WINDOW` | `1m` | ticket 발급 rate limit 집계 window |
| `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_USER` | `10` | 사용자별 ticket 발급 상한 |
| `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP` | `60` | IP별 ticket 발급 상한 |
| `CHAT_AUTH_WEB_SOCKET_TICKET_SESSION_FALLBACK_ENABLED` | `false` | docker/production에서 legacy session token WebSocket fallback 허용 여부 |
| `CHAT_API_CORS_ALLOWED_ORIGINS` | `*` | REST API CORS 허용 origin |
| `CHAT_WEBSOCKET_ALLOWED_ORIGINS` | `*` | WebSocket 허용 origin |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_QUEUE_MAX_PENDING_MESSAGES` | `128` | WebSocket session별 outbound pending queue 상한 |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_EXECUTOR_THREADS` | `32` | WebSocket outbound queue drain executor thread 수 |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_TIME_LIMIT_MILLIS` | `10000` | WebSocket session 단일 send 허용 시간 |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_BUFFER_SIZE_LIMIT_BYTES` | `524288` | WebSocket session send buffer 상한 |
| `CHAT_MESSAGE_SEQUENCE_TTL` | `24h` | Redis 메시지 시퀀스 키 TTL |
| `CHAT_MESSAGE_SEQUENCE_BLOCK_SIZE` | `1000` | Redis `INCRBY`로 한 번에 할당할 방별 `roomSeq` block 크기 |

## Client

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VITE_CHAT_API_BASE_URL` | `/api` | Axios base URL |
| `VITE_CHAT_WS_BASE_URL` | `/api/ws/chat` | WebSocket base URL |
| `VITE_CHAT_API_TIMEOUT_MS` | `30000` | API 요청 timeout |
| `VITE_CHAT_WS_MAX_RECONNECT_ATTEMPTS` | `5` | WebSocket 최대 재연결 횟수 |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:80` | Vite 개발 서버 proxy target |
