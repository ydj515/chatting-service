# API 레퍼런스

기본 컨텍스트 경로는 `/api`입니다.  
OpenAPI 스펙은 [`openapi.yaml`](openapi.yaml)을 참고하세요.

## REST API

### 사용자

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/users/register` | 회원가입 |
| `POST` | `/api/users/login` | 로그인 |
| `GET` | `/api/users/{id}` | 사용자 조회 |
| `GET` | `/api/users/me?userId=...` | 내 정보 조회 |
| `GET` | `/api/users/search?username=...` | 사용자 검색 |

### 채팅방

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/chat-rooms?createdBy=...` | 채팅방 생성 |
| `GET` | `/api/chat-rooms?userId=...` | 참여 중인 채팅방 목록 |
| `GET` | `/api/chat-rooms/{id}` | 채팅방 상세 조회 |
| `POST` | `/api/chat-rooms/{id}/members` | 채팅방 참여 (`{ "userId": ... }`) |
| `DELETE` | `/api/chat-rooms/{id}/members/me?userId=...` | 채팅방 퇴장 |
| `GET` | `/api/chat-rooms/{id}/members` | 채팅방 멤버 목록 |
| `GET` | `/api/chat-rooms/search?q=...&userId=...` | 채팅방 검색 |

### 메시지

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/chat-rooms/{id}/messages?userId=...` | 메시지 조회 |
| `GET` | `/api/chat-rooms/{id}/messages/cursor?userId=...&cursor=...&limit=50&direction=BEFORE` | 커서 기반 메시지 페이징 |

---

## WebSocket 프로토콜

### 접속

```
ws://localhost/api/ws/chat?userId={userId}
```

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
