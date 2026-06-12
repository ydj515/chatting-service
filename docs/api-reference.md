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
| `GET` | `/api/chat-rooms/{id}/messages/cursor?cursor=...&limit=50&direction=BEFORE` | 커서 기반 메시지 페이징 |

---

## WebSocket 프로토콜

### 접속

```
ws://localhost/api/ws/chat?token={sessionToken}
```

`sessionToken`은 `/api/users/login` 응답의 값입니다. Browser WebSocket API는 custom header를 설정할 수 없어 query parameter를 사용하지만, Nginx access log는 query string을 기록하지 않도록 구성합니다. `userId` query parameter는 인증 주체로 사용하지 않습니다.

### 클라이언트 -> 서버

```json
{
  "type": "SEND_MESSAGE",
  "chatRoomId": 1,
  "messageType": "TEXT",
  "content": "hello"
}
```

### 서버 -> 클라이언트

```json
{
  "type": "CHAT_MESSAGE",
  "id": 10,
  "content": "hello",
  "senderId": 1,
  "senderName": "alice",
  "sequenceNumber": 123,
  "chatRoomId": 1,
  "timestamp": "2024-01-01T00:00:00"
}
```
