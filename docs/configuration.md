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
| `WORKER_ROLES` | `message-writer,fanout,admin-export,room-policy` | `chat-worker-application`에서 활성화할 worker role 목록 |
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
| `CHAT_WORKER_ROOM_POLICY_POLL_DELAY_MILLIS` | `1000` | active room traffic snapshot을 읽어 heat/live feed/rate/slow-mode 정책을 자동 적용하는 주기 |
| `REDIS_PORT` | `6379` | Redis 내부 포트 |
| `CHAT_REDIS_MEMBERSHIP_TOPIC` | `chat.membership` | REST create/join 결과를 WebSocket 노드에 전파하는 Redis 제어 topic |
| `CHAT_REDIS_STREAMS_ROOM_STREAM_KEY_PREFIX` | `chat:stream:room:` | 방별 Redis Streams key prefix |
| `CHAT_REDIS_STREAMS_KNOWN_STREAMS_KEY` | `chat:stream:rooms` | worker가 poll할 stream key index set |
| `CHAT_REDIS_STREAMS_DEAD_LETTER_STREAM_KEY_PREFIX` | `chat:stream:dlq:` | worker consumer group별 dead letter stream key prefix |
| `CHAT_REDIS_STREAMS_SHARD_COUNT` | `1` | room stream shard 개수 |
| `CHAT_REDIS_ADMISSION_KEY_PREFIX` | `chat:admission:room:` | 메시지 수락 rate limit/slow mode Redis key prefix. 실제 key는 Redis Cluster hash tag를 포함해 `<prefix>{roomId}:...` 형태 |
| `CHAT_REDIS_ADMISSION_RATE_LIMIT_WINDOW_TTL` | `2s` | 초당 rate limit bucket key 정리 TTL. 고정 1초 bucket을 다음 초 이후까지 보존하기 위한 값 |
| `CHAT_AUTH_SESSION_SECRET` | `local-development-session-secret-change-me` | 로그인 session token HMAC 서명 secret. 운영 환경에서는 반드시 교체 |
| `CHAT_AUTH_SESSION_TTL` | `12h` | 로그인 session token TTL |
| `CHAT_AUTH_SESSION_TOKEN_QUERY_PARAM` | `token` | local/dev 호환 모드에서만 허용할 legacy session token query parameter |
| `CHAT_AUTH_WEB_SOCKET_TICKET_TTL` | `30s` | WebSocket one-time ticket TTL |
| `CHAT_AUTH_WEB_SOCKET_TICKET_QUERY_PARAM` | `ticket` | WebSocket handshake ticket query parameter |
| `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_WINDOW` | `1m` | ticket 발급 rate limit 집계 window |
| `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_USER` | `10` | 사용자별 ticket 발급 상한 |
| `CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP` | `60` | IP별 ticket 발급 상한 |
| `CHAT_AUTH_WEB_SOCKET_TICKET_SESSION_FALLBACK_ENABLED` | `false` | docker/production에서 legacy session token WebSocket fallback 허용 여부 |

### Redis Cache Serialization

Redis cache value serializer는 Kotlin/JavaTime module과 함께 `GenericJackson2JsonRedisSerializer` default typing을 사용한다. Phase 6 runtime smoke에서 `roomAdmissionPolicies` cache의 `RoomAdmissionPolicy`가 type hint 없이 저장되어 `LinkedHashMap`으로 복원되는 문제가 확인되었고, 이 경우 WebSocket send path의 admission check가 `ClassCastException`으로 실패한다. 따라서 Redis cache를 새 serializer로 읽기 전에 과거 type hint 없는 cache value가 남아 있으면 TTL 만료를 기다리거나 해당 cache key를 삭제해야 한다.

| `CHAT_API_CORS_ALLOWED_ORIGINS` | `*` | REST API CORS 허용 origin |
| `CHAT_WEBSOCKET_ALLOWED_ORIGINS` | `*` | WebSocket 허용 origin |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_QUEUE_MAX_PENDING_MESSAGES` | `128` | WebSocket session별 outbound pending queue 상한 |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_EXECUTOR_THREADS` | `32` | WebSocket outbound queue drain executor thread 수 |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_TIME_LIMIT_MILLIS` | `10000` | WebSocket session 단일 send 허용 시간 |
| `CHAT_WEBSOCKET_GATEWAY_OUTBOUND_SEND_BUFFER_SIZE_LIMIT_BYTES` | `524288` | WebSocket session send buffer 상한 |
| `CHAT_MESSAGE_SEQUENCE_TTL` | `24h` | Redis 메시지 시퀀스 키 TTL. `roomSeq`는 메시지마다 Redis `INCR 1`로 발급하며 block 선할당은 사용하지 않음 |
| `CHAT_CACHE_ROOM_ADMISSION_POLICIES_TTL` | `10s` | `room_storage_configs`의 rate limit/slow mode 정책 캐시 TTL. admin 정책 변경 시 해당 방 캐시는 즉시 evict |
| `CHAT_ROOM_POLICY_HOT_MESSAGES_PER_SECOND` | `1000` | room heat `HOT` 전환 초당 메시지 임계값 |
| `CHAT_ROOM_POLICY_VERY_HOT_MESSAGES_PER_SECOND` | `5000` | room heat `VERY_HOT` 전환 초당 메시지 또는 1분 p95 임계값 |
| `CHAT_ROOM_POLICY_OVERLOAD_WRITER_LAG_MILLIS` | `3000` | writer lag 기반 `OVERLOAD` 전환 임계값 |
| `CHAT_ROOM_POLICY_OVERLOAD_FANOUT_LAG_MILLIS` | `3000` | fanout lag 기반 `OVERLOAD` 전환 임계값 |
| `CHAT_ROOM_POLICY_OVERLOAD_GATEWAY_QUEUE_DEPTH` | `128` | Gateway send queue 기반 `OVERLOAD` 전환 임계값 |
| `CHAT_ROOM_POLICY_NORMAL_LIVE_FEED_MAX_MESSAGES` | `1000` | `NORMAL/HOT` 기본 live feed 최대 메시지 수 |
| `CHAT_ROOM_POLICY_NORMAL_LIVE_FEED_MAX_AGE_SECONDS` | `60` | `NORMAL/HOT` 기본 live feed 최대 age |
| `CHAT_ROOM_POLICY_VERY_HOT_LIVE_FEED_MAX_MESSAGES` | `500` | `VERY_HOT` 자동 downgrade live feed 최대 메시지 수 |
| `CHAT_ROOM_POLICY_VERY_HOT_LIVE_FEED_MAX_AGE_SECONDS` | `30` | `VERY_HOT` 자동 downgrade live feed 최대 age |
| `CHAT_ROOM_POLICY_OVERLOAD_LIVE_FEED_MAX_MESSAGES` | `300` | `OVERLOAD` 자동 downgrade live feed 최대 메시지 수 |
| `CHAT_ROOM_POLICY_OVERLOAD_LIVE_FEED_MAX_AGE_SECONDS` | `15` | `OVERLOAD` 자동 downgrade live feed 최대 age |
| `CHAT_ROOM_POLICY_VERY_HOT_ROOM_RATE_LIMIT_PER_SECOND` | `5000` | `VERY_HOT` 자동 room rate limit |
| `CHAT_ROOM_POLICY_OVERLOAD_ROOM_RATE_LIMIT_PER_SECOND` | `1000` | `OVERLOAD` 자동 room rate limit |
| `CHAT_ROOM_POLICY_HOT_SLOW_MODE_SECONDS` | `1` | `HOT` 자동 slow mode |
| `CHAT_ROOM_POLICY_VERY_HOT_SLOW_MODE_SECONDS` | `1` | `VERY_HOT` 자동 slow mode |
| `CHAT_ROOM_POLICY_OVERLOAD_SLOW_MODE_SECONDS` | `3` | `OVERLOAD` 자동 slow mode |
| `CHAT_ROOM_POLICY_TRAFFIC_KEY_PREFIX` | `chat:room-traffic:` | accepted message traffic counter Redis key prefix. 실제 key는 `<prefix>{roomId}:sec:<epochSecond>` |
| `CHAT_ROOM_POLICY_ACTIVE_ROOMS_KEY` | `chat:room-traffic:active-rooms` | 최근 traffic이 있는 room id를 추적하는 Redis sorted set key |
| `CHAT_ROOM_POLICY_TRAFFIC_WINDOW_SECONDS` | `60` | room traffic snapshot/p95 계산 window |
| `CHAT_ROOM_POLICY_TRAFFIC_COUNTER_TTL_SECONDS` | `120` | room traffic second counter TTL |

`room_storage_configs.auto_policy_enabled`는 `room-policy` worker의 자동 heat/live feed/rate/slow-mode upsert 허용 여부다. admin policy override는 기본적으로 이 값을 `false`로 바꿔 수동 정책을 보호하며, 자동 정책을 다시 허용하려면 admin API에서 `autoPolicyEnabled=true`를 명시한다.

`room_storage_configs.moderator_priority`는 `OWNER/ADMIN` 역할이 Redis admission rate limit과 slow mode를 우회할지 결정한다. 기본값은 `true`이며, `false`로 설정된 방에서는 운영자 역할도 일반 사용자와 같은 admission 제한을 받는다.

> 2026-06-18 기준으로 `CHAT_MESSAGE_SEQUENCE_BLOCK_SIZE` 설정은 제거했다. 방 `id=3`에서 WebSocket Gateway별 sequence block 선할당 때문에 실제 생성 시간은 `room_seq=1001..1016` 이후 `room_seq=46..53`이었지만, 클라이언트가 `roomSeq`로 정렬하면서 나중 메시지가 먼저 보이는 문제가 확인되었다. 트위치 같은 스트리밍 채팅에서는 같은 방의 실시간 feed가 서버 수락 순서를 따라야 하므로, 처리량 최적화용 block 선할당보다 방 단위 전역 `INCR 1` 순서를 우선한다.

### Phase 6: Fanout Owner Lease

Production에서 `fanout` worker를 여러 replica로 늘릴 때는 Redis TTL lease 기반 방별 owner를 활성화한다. 현재 코드와 Docker profile의 기본 운영값은 TTL `10000ms`, renew interval `3000ms`다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_ENABLED` | `true` | fanout worker 다중화 시 방/stream shard별 owner lease 사용 여부 |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_TTL_MILLIS` | `10000` | owner lease TTL. 이 시간이 지나면 다른 worker가 takeover 가능 |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_RENEW_INTERVAL_MILLIS` | `3000` | owner worker가 lease를 갱신하는 주기. 실제 구현은 보유 lease별 마지막 renew 시각을 기준으로 이 interval 이후에만 Redis TTL renew Lua를 호출 |
| `CHAT_WORKER_FANOUT_OWNER_LEASE_KEY_PREFIX` | `chat:fanout:owner:room:` | owner lease Redis key prefix. 최종 key는 `<prefix><roomId>:shard:<streamShard>` |

> Redis Streams consumer group만으로 fanout worker를 여러 대 띄우면 같은 방의 batch publish 순서가 뒤집힐 수 있다. owner lease는 같은 room/stream shard에서 owner worker 하나만 `XREADGROUP`, publish, `XACK`를 수행하도록 강제하기 위한 production safety gate다.

Phase 6 owner takeover smoke는 `scripts/phase6-fanout-takeover-smoke.mjs`로 실행한다. 이 스크립트는 Redis owner lease value를 읽어 실제 owner worker container를 kill하고, TTL 10초 이후 takeover된 fanout의 수신량과 `roomSeq` 순서를 검증한다. `load-chat`의 `--drain-wait`는 kill 동안 쌓인 backlog가 큰 batch로 전파되는 시간을 기다리는 옵션이며, raw WebSocket client는 continuation frame을 join해 큰 `CHAT_MESSAGE_BATCH`도 누락 없이 검증한다.

## Client

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VITE_CHAT_API_BASE_URL` | `/api` | Axios base URL |
| `VITE_CHAT_WS_BASE_URL` | `/api/ws/chat` | WebSocket base URL |
| `VITE_CHAT_API_TIMEOUT_MS` | `30000` | API 요청 timeout |
| `VITE_CHAT_WS_MAX_RECONNECT_ATTEMPTS` | `5` | WebSocket 최대 재연결 횟수 |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:80` | Vite 개발 서버 proxy target |
