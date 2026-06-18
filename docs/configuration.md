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
| `CHAT_DATASOURCE_READ_ENABLED` | `true` | backend가 read-only message history datasource를 별도로 사용할지 여부 |
| `DB_READ_HOST` | `postgres-replica` | read-only message history datasource host |
| `DB_READ_PORT` | `5432` | read-only message history datasource port |
| `DB_READ_USERNAME` | `DB_USERNAME` | read-only datasource 사용자. 미설정 시 primary DB 사용자 사용 |
| `DB_READ_PASSWORD` | `DB_PASSWORD` | read-only datasource 비밀번호. 미설정 시 primary DB 비밀번호 사용 |
| `CHAT_DATASOURCE_READ_LATEST_HISTORY_MAX_REPLICA_LAG` | `2s` | 최신 사용자 history 조회가 primary로 fallback되는 read replica lag 임계값 |
| `POSTGRES_REPLICATION_USER` | `chat_replicator` | PostgreSQL streaming replication 사용자 |
| `POSTGRES_REPLICATION_PASSWORD` | `replicatorpass` | PostgreSQL streaming replication 비밀번호 |
| `CHAT_MESSAGE_RETENTION_DAYS` | `100` | 메시지 파티션 보관 기간 |
| `CHAT_MESSAGE_HASH_PARTITIONS` | `16` | 일별 `chat_messages` partition 아래 생성할 hash subpartition 수 |
| `CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY` | `false` | archive 성공 후 partition detach/drop 여부 |
| `CHAT_PARTITION_ARCHIVE_INTERVAL_SECONDS` | `86400` | archive worker 실행 주기 |
| `CHAT_ADMIN_TOKEN` | 필수 | `chat-admin` API의 `X-Admin-Token` 검증 값. Docker/admin 실행 시 명시하지 않으면 시작 실패 |
| `CHAT_ADMIN_ACTOR` | `admin-local` | shared token 인증 시 audit log에 기록할 기본 관리자 actor |
| `CHAT_ADMIN_DEFAULT_LIMIT` | `50` | 관리자 history/search 기본 page size |
| `CHAT_ADMIN_MAX_LIMIT` | `100` | 관리자 history/search 최대 page size |
| `CHAT_ADMIN_EXPORT_DIRECTORY` | `/tmp/chat-admin-exports` | admin export worker가 CSV 산출물을 기록할 로컬 디렉터리 |
| `WORKER_ROLES` | `message-writer,fanout,admin-export` | `chat-worker-application`에서 활성화할 worker role 목록 |
| `CHAT_WORKER_POLL_DELAY_MILLIS` | `100` | worker scheduler poll 간격 |
| `CHAT_WORKER_WRITER_CONSUMER_GROUP` | `message-writer` | Redis Streams writer consumer group 이름 |
| `CHAT_WORKER_WRITER_READ_COUNT` | `100` | writer worker가 poll 1회에 읽을 최대 stream record 수 |
| `CHAT_WORKER_WRITER_MIN_IDLE_MILLIS` | `30000` | writer worker가 pending record를 claim하기 전 필요한 최소 idle 시간 |
| `CHAT_WORKER_WRITER_CLAIM_INTERVAL_MILLIS` | `10000` | writer worker가 pending claim을 수행하는 최소 주기 |
| `CHAT_WORKER_WRITER_MAX_DELIVERY_COUNT` | `5` | writer worker record 처리 실패 후 DLQ로 보내는 delivery count 임계값 |
| `CHAT_WORKER_FANOUT_CONSUMER_GROUP` | `fanout` | Redis Streams fanout consumer group 이름 |
| `CHAT_WORKER_FANOUT_READ_COUNT` | `100` | fanout worker가 poll 1회에 읽을 최대 stream record 수 |
| `CHAT_WORKER_FANOUT_MIN_IDLE_MILLIS` | `30000` | fanout worker가 pending record를 claim하기 전 필요한 최소 idle 시간 |
| `CHAT_WORKER_FANOUT_CLAIM_INTERVAL_MILLIS` | `10000` | fanout worker가 pending claim을 수행하는 최소 주기 |
| `CHAT_WORKER_FANOUT_MAX_DELIVERY_COUNT` | `5` | fanout worker record 처리 실패 후 DLQ로 보내는 delivery count 임계값 |
| `REDIS_PORT` | `6379` | Redis 내부 포트 |
| `CHAT_REDIS_MEMBERSHIP_TOPIC` | `chat.membership` | REST create/join 결과를 WebSocket 노드에 전파하는 Redis 제어 topic |
| `CHAT_REDIS_STREAMS_ROOM_STREAM_KEY_PREFIX` | `chat:stream:room:` | 방별 Redis Streams key prefix |
| `CHAT_REDIS_STREAMS_KNOWN_STREAMS_KEY` | `chat:stream:rooms` | worker가 poll할 stream key index set |
| `CHAT_REDIS_STREAMS_DEAD_LETTER_STREAM_KEY_PREFIX` | `chat:stream:dlq:` | worker consumer group별 dead letter stream key prefix |
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
| `CHAT_MESSAGE_SEQUENCE_TTL` | `24h` | Redis 메시지 시퀀스 키 TTL. `roomSeq`는 메시지마다 Redis `INCR 1`로 발급하며 block 선할당은 사용하지 않음 |

> 2026-06-18 기준으로 `CHAT_MESSAGE_SEQUENCE_BLOCK_SIZE` 설정은 제거했다. 방 `id=3`에서 WebSocket Gateway별 sequence block 선할당 때문에 실제 생성 시간은 `room_seq=1001..1016` 이후 `room_seq=46..53`이었지만, 클라이언트가 `roomSeq`로 정렬하면서 나중 메시지가 먼저 보이는 문제가 확인되었다. 트위치 같은 스트리밍 채팅에서는 같은 방의 실시간 feed가 서버 수락 순서를 따라야 하므로, 처리량 최적화용 block 선할당보다 방 단위 전역 `INCR 1` 순서를 우선한다.

### Phase 6 예정: Fanout Owner Lease

Production에서 `fanout` worker를 여러 replica로 늘릴 때는 Redis TTL lease 기반 방별 owner를 먼저 활성화한다. 아래 변수는 Phase 6 구현 대상이며, 현재 문서는 운영 기본값을 고정하기 위한 기준이다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED` | `true` | fanout worker 다중화 시 방/stream shard별 owner lease 사용 여부 |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_TTL_MILLIS` | `10000` | owner lease TTL. 이 시간이 지나면 다른 worker가 takeover 가능 |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_RENEW_INTERVAL_MILLIS` | `3000` | owner worker가 lease를 갱신하는 주기 |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_KEY_PREFIX` | `chat:fanout:owner:room:` | owner lease Redis key prefix. 최종 key는 `<prefix><roomId>:shard:<streamShard>` |

> Redis Streams consumer group만으로 fanout worker를 여러 대 띄우면 같은 방의 batch publish 순서가 뒤집힐 수 있다. owner lease는 같은 room/stream shard에서 owner worker 하나만 `XREADGROUP`, publish, `XACK`를 수행하도록 강제하기 위한 production safety gate다.

## Client

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VITE_CHAT_API_BASE_URL` | `/api` | Axios base URL |
| `VITE_CHAT_WS_BASE_URL` | `/api/ws/chat` | WebSocket base URL |
| `VITE_CHAT_API_TIMEOUT_MS` | `30000` | API 요청 timeout |
| `VITE_CHAT_WS_MAX_RECONNECT_ATTEMPTS` | `5` | WebSocket 최대 재연결 횟수 |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:80` | Vite 개발 서버 proxy target |
