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

### 채팅방

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/chat-rooms` | 채팅방 생성 |
| `GET` | `/api/chat-rooms` | 참여 중인 채팅방 목록 |
| `GET` | `/api/chat-rooms/{id}` | 채팅방 상세 조회 |
| `POST` | `/api/chat-rooms/{id}/members` | 채팅방 참여 |
| `DELETE` | `/api/chat-rooms/{id}/members/me` | 채팅방 퇴장 |
| `GET` | `/api/chat-rooms/{id}/members` | 채팅방 멤버 목록 |
| `GET` | `/api/chat-rooms/search?q=...` | 채팅방 검색 |

### 메시지

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/chat-rooms/{id}/messages` | 메시지 조회 |
| `GET` | `/api/chat-rooms/{id}/messages/cursor?cursor=...&limit=50&direction=BEFORE` | `roomSeq` 기준 커서 기반 메시지 페이징 (장기적으로 cursor는 보안 및 유연성을 위해 Long 대신 Base64 등 Opaque 문자열로 변경 검토) |
| `GET` | `/api/chat-rooms/{id}/messages/gap?afterSeq=12345&limit=50` | 재연결 후 누락 메시지 보정용 gap fill |

### 관리자

관리자 API는 `X-Admin-Token` 헤더가 필요합니다. Phase 5 로컬 기본값은 `local-admin-token`이며 운영 환경에서는 `CHAT_ADMIN_TOKEN`으로 교체합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/admin/chat-rooms/{roomId}/messages?from=...&to=...&cursor=...&limit=50` | canonical store 기준 방별/시간대별 history 조회 |
| `GET` | `/api/admin/messages/search?q=hello&mode=FTS&roomId=1&senderId=7&from=...&to=...&cursor=...&limit=50` | PostgreSQL 메시지 검색. 기본 `mode=FTS`, 부분 문자열 fallback은 `mode=CONTAINS` |
| `GET` | `/api/admin/rooms/{roomId}/status` | room heat, bounded live feed, rate limit, replica/search latency 상태 조회 |
| `POST` | `/api/admin/exports/messages` | 메시지 export job 생성. `admin-export` worker가 pending job을 claim해 CSV 산출물을 생성 |

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
