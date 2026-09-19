# API 레퍼런스

기본 컨텍스트 경로는 `/api`입니다.  
OpenAPI 스펙은 [`openapi.yaml`](openapi.yaml)을 참고하세요.

로그인 이후 보호된 REST API는 `Authorization: Bearer {sessionToken}` 헤더의 사용자만 인증 주체로 사용합니다. `userId`, `createdBy` query/body 값은 인증 대체 수단으로 사용하지 않습니다.

## REST API

### 사용자

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/users/register` | 회원가입 |
| `POST` | `/api/users/login` | 로그인. 응답에 `sessionToken`, `tokenType`, `expiresAt` 포함 |
| `GET` | `/api/users/{id}` | 사용자 조회 |
| `GET` | `/api/users/me` | 내 정보 조회 |
| `GET` | `/api/users/search?username=...` | 사용자 검색 |

### WebSocket Ticket

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/ws-tickets` | WebSocket one-time ticket 발급. `Authorization: Bearer {sessionToken}` 필요 |

티켓은 발급에 사용한 세션에 연결됩니다. 개별 로그아웃이나 사용자 전체 토큰 철회 후에는
이미 발급된 티켓도 소비할 수 없습니다. 티켓 만료는 원래 세션 만료를 넘지 않으며, Redis에는
세션 원문 대신 SHA-256 지문만 보관합니다. 세션 연결 정보가 없는 구버전 티켓은 재발급해야 합니다.

### 채팅방

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/chat-rooms` | 채팅방 생성 |
| `GET` | `/api/chat-rooms` | 참여 중인 채팅방 목록 |
| `GET` | `/api/chat-rooms/{id}` | 인증된 활성 멤버만 상세 조회 가능 |
| `POST` | `/api/chat-rooms/{id}/members` | 채팅방 참여 |
| `DELETE` | `/api/chat-rooms/{id}/members/me` | 채팅방 퇴장 |
| `GET` | `/api/chat-rooms/{id}/members` | 인증된 활성 멤버만 멤버 목록 조회 가능 |
| `GET` | `/api/chat-rooms/search?q=...` | 인증된 사용자의 방 검색. 검색 결과는 메타데이터만 제공하며 `lastMessage`는 `null` |

### 메시지

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/chat-rooms/{id}/messages` | 메시지 조회 |
| `GET` | `/api/chat-rooms/{id}/messages/cursor?cursorToken=...&limit=50&direction=BEFORE` | `roomSeq` 기준 커서 기반 메시지 페이징. 신규 client는 opaque `cursorToken`/`nextCursorToken`을 사용하고, legacy numeric `cursor`/`nextCursor`는 public client migration 이후 `2 releases or 30 days` 중 더 긴 기간 동안 유지 |
| `GET` | `/api/chat-rooms/{id}/messages/gap?afterSeq=12345&limit=50` | 재연결 후 누락 메시지 보정용 gap fill |

### 관리자

관리자 API는 `X-Admin-Token` 헤더가 필요합니다. `CHAT_ADMIN_TOKEN`은 Docker/admin 실행 시 명시해야 하며, 기본 토큰은 제공하지 않습니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/admin/chat-rooms/{roomId}/messages?from=...&to=...&cursor=...&limit=50` | canonical store 기준 방별/시간대별 history 조회. `cursor`는 `createdAt + roomSeq + messageId` 기반 opaque cursor이며 클라이언트는 그대로 재전송 |
| `GET` | `/api/admin/messages/search?q=hello&mode=FTS&roomId=1&senderId=7&from=...&to=...&cursor=...&limit=50` | PostgreSQL 메시지 검색. 기본 `mode=FTS`, 부분 문자열 fallback은 `mode=CONTAINS`. `cursor`는 `createdAt + roomSeq + messageId` 기반 opaque cursor이며 클라이언트는 그대로 재전송 |
| `GET` | `/api/admin/rooms/{roomId}/status` | room heat, bounded live feed, rate limit, replica/search latency 상태 조회 |
| `PATCH` | `/api/admin/rooms/{roomId}/policy` | bounded live feed, room/user rate limit, slow mode, moderator priority 정책 override. 기본적으로 `autoPolicyEnabled=false`가 되어 room-policy worker가 해당 방의 수동 정책을 덮어쓰지 않음. `rateLimitPerSecond`, `userRateLimitPerSecond`, `slowModeSeconds`의 누락/null은 기존 값 유지이며, 제한 해제는 `clearRateLimit=true`, `clearUserRateLimit=true`, `clearSlowMode=true`로 명시 |
| `POST` | `/api/admin/exports/messages` | 메시지 export job 생성. `admin-export` worker가 pending job을 claim해 CSV 산출물을 생성 |
| `GET` | `/api/admin/exports/{jobId}` | export job 상태 조회. 완료된 job은 안정적인 `s3://` `outputUri`와 짧은 TTL의 `downloadUrl`/`downloadUrlExpiresAt`을 반환 |

관리자 프론트는 일반 사용자용 `client`와 분리된 `client-admin` 모듈입니다.

---

## WebSocket 프로토콜

### 접속

먼저 REST API로 WebSocket 전용 ticket을 발급합니다.

```http
POST /api/ws-tickets
Authorization: Bearer {sessionToken}
```

응답:

```json
{
  "ticket": "base64url-random-256bit",
  "expiresAt": "2026-06-13T00:00:30"
}
```

그 다음 발급받은 ticket으로 WebSocket을 연결합니다.

```
ws://localhost/api/ws/chat?ticket={ticket}
```

`sessionToken`은 REST API의 Authorization header에만 사용합니다. WebSocket URL에는 짧은 TTL의 단일 사용 ticket만 포함합니다. production/docker 설정에서는 기존 `token` query fallback을 허용하지 않습니다. `userId` query parameter는 인증 주체로 사용하지 않습니다.

### 클라이언트 -> 서버

```json
{
  "type": "SEND_MESSAGE",
  "chatRoomId": 1,
  "messageType": "TEXT",
  "content": "hello",
  "clientMessageId": "client-generated-id"
}
```

### 서버 -> 클라이언트

송신자 ACK:

```json
{
  "type": "MESSAGE_ACCEPTED",
  "id": 10,
  "messageId": "msg_m8abcd123_xYz...",
  "clientMessageId": "client-generated-id",
  "roomId": 1,
  "chatRoomId": 1,
  "roomSeq": 123,
  "sequenceNumber": 123,
  "timestamp": "2026-06-12T12:00:00"
}
```

단건 메시지:

```json
{
  "type": "CHAT_MESSAGE",
  "id": 10,
  "messageId": "msg_m8abcd123_xYz...",
  "clientMessageId": "client-generated-id",
  "content": "hello",
  "messageType": "TEXT",
  "senderId": 1,
  "senderName": "alice",
  "sequenceNumber": 123,
  "roomSeq": 123,
  "streamShard": 0,
  "writeShard": 0,
  "fanoutShard": 0,
  "chatRoomId": 1,
  "timestamp": "2026-06-12T12:00:00"
}
```

Batch 메시지:

```json
{
  "type": "CHAT_MESSAGE_BATCH",
  "chatRoomId": 1,
  "messages": [
    {
      "type": "CHAT_MESSAGE",
      "id": 10,
      "messageId": "msg_m8abcd123_xYz...",
      "clientMessageId": "client-generated-id",
      "content": "hello",
      "messageType": "TEXT",
      "senderId": 1,
      "senderName": "alice",
      "sequenceNumber": 123,
      "roomSeq": 123,
      "streamShard": 0,
      "writeShard": 0,
      "fanoutShard": 0,
      "chatRoomId": 1,
      "timestamp": "2026-06-12T12:00:00"
    }
  ],
  "timestamp": "2026-06-12T12:00:00"
}
```

클라이언트는 `messageId`를 기준으로 중복 제거하고, `roomSeq` 기준으로 방 안의 표시 순서를 정렬합니다. 같은 `clientMessageId`를 재전송하면 서버는 새 메시지를 저장하지 않고 기존 `messageId`, `roomSeq`를 가진 ACK를 반환합니다.

### WebSocket room delivery authorization

Room fan-out rechecks active membership in the primary database in one batch for local recipients.
A missed LEAVE notification cannot authorize later deliveries: departed recipients are removed
from local subscriptions. Database lookup failures stop that batch before enqueueing any data;
clients can recover authorized history using the existing gap API after recovery.
