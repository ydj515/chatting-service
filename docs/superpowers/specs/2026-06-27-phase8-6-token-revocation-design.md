# Phase 8.6 인증 토큰 revocation과 global suspend 설계

## 1. 문제 이해 / 요구사항 정리

### 조건

- 현재 `HmacSessionTokenService`는 HMAC 서명과 TTL만 검증한다.
- API 인증(`AuthenticatedUserResolver`), WebSocket fallback handshake(`WebSocketHandshakeInterceptor`), WebSocket ticket 발급은 모두 `SessionTokenService.authenticate()` 결과에 의존한다.
- WebSocket one-time ticket은 Redis에 짧은 TTL로 저장되고, session token으로 발급받는다.
- Phase 8.5는 room scoped `MUTE`/`BAN`만 실제 제재로 적용했고, 전역 `SUSPEND`와 session token revocation은 Phase 8.6 범위로 남겼다.
- `chat-admin`과 WebSocket gateway는 다른 애플리케이션 프로세스로 실행될 수 있으므로, admin 제재 생성 시 gateway의 로컬 세션을 직접 참조할 수 없다.

### 목표

- 로그아웃, 제재, 유출 대응 시 session token을 TTL 만료 전에도 즉시 무효화한다.
- `GLOBAL + SUSPEND` 사용자 제재를 허용하고, 생성 시 해당 사용자의 기존 session token을 강제 무효화한다.
- suspend 생성 시 이미 열린 WebSocket 세션도 즉시 종료한다.
- 기존 API/WebSocket 인증 호출부는 가능한 유지하고, revocation 판단은 `SessionTokenService.authenticate()` 내부에 모은다.
- token 원문은 Redis나 로그에 저장하지 않는다.

### 비범위

- refresh token 체계 도입은 이번 Phase 범위가 아니다.
- 사용자 DB 스키마에 token version 컬럼을 추가하지 않는다.
- HMAC 키 다중 보관을 통한 장기간 graceful key rotation은 이번 Phase에서 문서화 가능한 후속으로 둔다.
- 이미 발급된 WebSocket one-time ticket 자체를 별도 denylist로 추적하지 않는다. ticket TTL이 짧고, 새 ticket 발급은 session token revocation으로 차단한다.

## 2. 해결 접근

### 선택한 접근

Redis 기반 denylist와 user-wide `revoke-after` marker를 함께 사용한다.

- token 단위 revoke: 특정 session token 하나만 무효화한다.
- user 단위 revoke-after: 특정 시각 이전에 발급된 해당 사용자의 모든 session token을 무효화한다.
- global suspend 생성: user revoke-after를 기록하고, Redis pub/sub 제어 이벤트로 모든 gateway에 해당 사용자의 로컬 WebSocket 세션 종료를 요청한다.

이 방식은 현재 stateless HMAC token 구조를 유지하면서 즉시 무효화 요구를 충족한다. DB 조회를 인증 hot path에 넣지 않고 Redis `GET` 2회 수준으로 제한할 수 있다.

## 3. 컴포넌트 설계

### SessionTokenRevocationStore

신규 domain port를 둔다.

```kotlin
interface SessionTokenRevocationStore {
    fun revokeToken(token: String, expiresAt: Instant)
    fun revokeUserTokens(userId: Long, revokedAt: Instant)
    fun isTokenRevoked(token: String): Boolean
    fun userRevokedAt(userId: Long): Instant?
}
```

Redis 구현체는 token 원문 대신 SHA-256 + Base64URL hash를 key에 사용한다.

- token revoke key: `chat:auth:session:revoked:token:{tokenHash}`
- user revoke key: `chat:auth:session:revoked:user:{userId}`

token revoke key의 TTL은 `expiresAt - now`로 둔다. user revoke key는 session TTL보다 조금 길게 유지한다. 예를 들어 `session.ttl + 1h`를 기본값으로 두면, 모든 기존 token이 자연 만료된 뒤 Redis 공간이 회수된다.

Redis 장애 시 인증 정책은 fail-closed를 기본값으로 둔다. revocation store 조회가 실패하면 token을 인증하지 않는다. 인증 우회보다 일시적인 인증 실패가 더 안전하기 때문이다.

### HmacSessionTokenService

새로 발급하는 token payload는 `issuedAt`을 포함한다.

기존 payload:

```text
userId:expiresAt:nonce
```

신규 payload:

```text
userId:issuedAt:expiresAt:jti
```

외부 token prefix는 기존 `v1`을 유지하고, payload field 수로 legacy/new token을 구분한다. 기존 token을 갑자기 모두 깨지 않기 위해 legacy 3-field token도 파싱한다.

인증 순서는 다음과 같다.

1. token 구조와 버전 확인
2. HMAC 서명 constant-time 비교
3. payload decode와 claim 파싱
4. TTL 만료 확인
5. token hash denylist 확인
6. user revoke-after 확인
7. 통과 시 `AuthenticatedSession` 반환

legacy 3-field token은 `issuedAt`이 없으므로 user revoke-after marker가 존재하면 거부한다. 이렇게 하면 Phase 8.6 배포 직후 suspend나 강제 로그아웃이 기존 token에도 보수적으로 적용된다.

`SessionTokenService`는 다음 메서드를 추가한다.

```kotlin
interface SessionTokenService {
    fun issueToken(userId: Long): SessionToken
    fun authenticate(token: String): AuthenticatedSession?
    fun revokeToken(token: String): Boolean
    fun revokeUserTokens(userId: Long)
}
```

`revokeToken`은 token이 유효하게 파싱되고 만료 전일 때만 Redis token denylist에 기록한다. 이미 만료됐거나 변조된 token은 `false`를 반환한다.

### GLOBAL SUSPEND sanction

`UserSanctionType`은 실제 운영 타입으로 `SUSPEND`를 갖는다.

```kotlin
enum class UserSanctionType {
    MUTE,
    BAN,
    SUSPEND,
}
```

정책은 다음처럼 제한한다.

- `MUTE`, `BAN`: `ROOM` scope만 허용한다.
- `SUSPEND`: `GLOBAL` scope만 허용한다.
- `GLOBAL` sanction은 `roomId = null`이어야 한다.
- `ROOM` sanction은 `roomId`가 있어야 한다.
- `expiresAt`이 과거/현재면 생성하지 않는다.

DB 제약 `ck_user_sanctions_type`은 `MUTE`, `BAN`, `SUSPEND`를 허용하고, `ck_user_sanctions_scope_type`은 `ROOM + MUTE/BAN`, `GLOBAL + SUSPEND` 조합만 허용한다.

`AdminModerationServiceImpl.createSanction()`은 `GLOBAL + SUSPEND` 생성 성공 후 다음 작업을 수행한다.

1. audit log 기록
2. `SessionTokenService.revokeUserTokens(userId)` 호출
3. `SessionControlPublisher.forceLogoutUser(userId, reason = "suspended")` 호출

순서는 sanction 저장과 audit commit이 먼저다. token revocation과 force logout publish는 sanction transaction이 성공적으로 commit된 뒤 실행한다. commit 실패 시 외부 부수효과가 먼저 발생하지 않도록 하기 위해서다.

`revokeSanction()`은 suspend sanction을 비활성화하지만 이미 revoke된 token은 되살리지 않는다. 사용자는 suspend 해제 이후 새 로그인으로 새 token을 받아야 한다.

### 인증 경로

API controller와 WebSocket handshake는 계속 `SessionTokenService.authenticate()`를 호출한다. 따라서 별도 controller/interceptor 변경 없이 다음 경로가 같이 보호된다.

- REST API bearer token 인증
- WebSocket handshake bearer/query fallback 인증
- WebSocket one-time ticket 발급 전 인증

WebSocket ticket 소비는 이미 발급된 짧은 TTL ticket만 대상으로 하므로 이번 Phase에서 별도 denylist를 두지 않는다.

### 강제 WebSocket 종료

`chat-admin`이 gateway의 메모리 세션을 직접 닫을 수 없으므로 Redis pub/sub 제어 채널을 추가한다.

- topic: `chat.session.control`
- event type: `FORCE_LOGOUT_USER`
- payload:

```json
{
  "id": "server-1-session-control-...",
  "serverId": "server-1-session-control-...",
  "type": "FORCE_LOGOUT_USER",
  "userId": 7,
  "reason": "suspended",
  "timestamp": "2026-06-27T00:00:00"
}
```

gateway 쪽 `WebSocketSessionManager`는 control event를 받으면 `closeSessionsForUser(userId, CloseStatus(4003, "Session revoked"))`를 실행한다.

신규 domain port는 `SessionControlPublisher`로 둔다. Redis 구현체는 `RedisSessionControlBroker`로 두고, publish와 subscribe를 모두 담당한다. 기존 `RedisMessageBroker`는 room fan-out과 membership 책임이 섞여 있으므로, 인증 제어 이벤트까지 넣지 않는다.

`WebSocketSessionManager`에는 public 메서드 하나를 추가한다.

```kotlin
fun closeSessionsForUser(userId: Long, closeStatus: CloseStatus = SESSION_REVOKED_STATUS)
```

세션 종료 시 기존 `removeSession()` 경로를 재사용해 room index와 outbound queue를 정리한다.

## 4. 데이터 흐름

### 로그인

1. `UserServiceImpl.login()`이 사용자 비밀번호를 검증한다.
2. active `GLOBAL + SUSPEND` sanction이 있으면 로그인을 거부한다.
3. `SessionTokenService.issueToken(userId)`가 `issuedAt`, `expiresAt`, `jti`를 포함한 HMAC token을 발급한다.
4. client는 bearer token으로 API와 WebSocket ticket API를 호출한다.

### 로그아웃

1. API가 bearer token을 받는다.
2. `SessionTokenService.revokeToken(token)`이 token을 검증하고 만료 전이면 token hash denylist를 Redis에 저장한다.
3. 이후 같은 token의 `authenticate()`는 실패한다.

로그아웃 endpoint는 `POST /logout`로 둔다. 현재 `UserController`가 `/login`, `/register`를 갖고 있으므로 같은 controller에 추가한다.

### Global suspend 생성

1. admin이 `POST /admin/moderation/sanctions`로 `GLOBAL + SUSPEND`를 생성한다.
2. `AdminModerationServiceImpl`이 scope/type 조합을 검증한다.
3. `UserSanctionJdbcRepository.create()`가 sanction을 저장한다.
4. audit log를 기록한다.
5. transaction commit 후 `SessionTokenService.revokeUserTokens(userId)`가 user revoke-after marker를 Redis에 저장한다.
6. transaction commit 후 `SessionControlPublisher`가 Redis control topic으로 force logout event를 publish한다.
7. 각 gateway가 해당 사용자의 로컬 WebSocket 세션을 닫는다.

### 인증 검증

1. token 서명과 만료를 확인한다.
2. token hash denylist를 확인한다.
3. user revoke-after marker를 확인한다.
4. 신규 token은 `issuedAt <= revokedAt`이면 거부한다.
5. legacy token은 해당 user의 revoke-after marker가 존재하면 거부한다.

## 5. 오류 처리와 운영 정책

- Redis revocation 조회 실패: 인증 실패로 처리한다.
- Redis revoke 기록 실패: logout 요청은 실패로 처리한다. suspend 후 commit된 post-commit revocation 실패는 호출자에게 실패로 노출될 수 있으나 DB sanction은 이미 유지된다.
- Redis force logout publish 실패: suspend 후 commit된 post-commit publish 실패는 호출자에게 실패로 노출될 수 있으나 DB sanction은 이미 유지된다.
- 이미 만료된 token logout: 멱등 성공 응답으로 처리하되 내부 `revokeToken`은 `false`를 반환할 수 있다.
- 이미 revoke된 token logout: 멱등 성공으로 처리한다.
- suspend 해제: 기존 token은 복구하지 않는다.

client-facing error에는 token hash, sanction id, internal Redis key를 포함하지 않는다.

## 6. 설정

`ChatAuthProperties.Session`에 다음 설정을 추가한다.

```kotlin
data class Session(
    val secret: String = "...",
    val ttl: Duration = Duration.ofHours(12),
    val tokenQueryParam: String = "token",
    val revocationKeyPrefix: String = "chat:auth:session:revoked:",
    val userRevocationGraceTtl: Duration = Duration.ofHours(1),
    val controlTopic: String = "chat.session.control",
)
```

환경 변수 예시는 다음과 같다.

- `CHAT_AUTH_SESSION_REVOCATION_KEY_PREFIX`
- `CHAT_AUTH_SESSION_USER_REVOCATION_GRACE_TTL`
- `CHAT_AUTH_SESSION_CONTROL_TOPIC`

## 7. 테스트 계획

### 단위 테스트

- `HmacSessionTokenServiceTest`
  - 발급 token은 인증된다.
  - 서명 변조 token은 인증되지 않는다.
  - 만료 token은 인증되지 않는다.
  - token denylist에 등록된 token은 인증되지 않는다.
  - user revoke-after 이전 발급 token은 인증되지 않는다.
  - user revoke-after 이후 발급 token은 인증된다.
  - legacy 3-field token은 user revoke-after marker가 있으면 인증되지 않는다.

- `RedisSessionTokenRevocationStoreTest`
  - token hash key에 원문 token을 저장하지 않는다.
  - token revoke key에 남은 만료 시간 기반 TTL을 건다.
  - user revoke-after marker를 epoch second로 저장하고 읽는다.

- `AdminModerationServiceImplTest`
  - `GLOBAL + SUSPEND` 생성은 허용한다.
  - `GLOBAL + SUSPEND` 생성 후 user token revoke와 force logout publish를 호출한다.
  - `ROOM + SUSPEND`는 거부한다.
  - `GLOBAL + MUTE/BAN`은 거부한다.

- `WebSocketSessionManagerTest`
  - `closeSessionsForUser`가 해당 user의 열린 local session만 닫고 index를 정리한다.

- `RedisSessionControlBrokerTest`
  - force logout event publish payload가 userId와 reason을 포함한다.
  - 수신한 force logout event는 local handler로 전달된다.

### 통합/회귀 테스트

- `AuthenticatedUserResolverTest`: revoke된 token은 인증 실패로 이어진다.
- `WebSocketHandshakeInterceptorTest`: revoke된 token fallback handshake는 401로 거부된다.
- `AdminModerationControllerTest`: `GLOBAL + SUSPEND` 요청이 service로 전달된다.
- `infra/postgres/message-partitions.sql` 제약에 `SUSPEND`가 포함된다.

### 검증 명령

```bash
./gradlew test --no-daemon
mise run verify:moderation
```

## 8. 복잡도

- token hash 계산 시간 복잡도: `O(L)` (`L`은 token 문자열 길이)
- token denylist 조회 시간 복잡도: `O(1)`
- user revoke-after 조회 시간 복잡도: `O(1)`
- session token 인증 추가 공간 복잡도: `O(1)`
- Redis revocation 저장 공간 복잡도: `O(T + U)`
  - `T`는 만료 전 token 단위 revoke 수다.
  - `U`는 user 단위 revoke-after marker가 남아 있는 사용자 수다.
- gateway force logout 처리 시간 복잡도: `O(C)`
  - `C`는 해당 user의 현재 local WebSocket session 수다.

## 9. 주의사항

> - Redis revocation store를 fail-closed로 두면 Redis 장애 시 인증 실패가 늘 수 있다. 보안 우선 정책으로 선택한다.
> - user revoke-after는 기존 token을 되살릴 수 없다. suspend 해제 후에는 새 로그인이 필요하다.
> - legacy 3-field token은 `issuedAt`이 없으므로 user revoke marker가 있으면 보수적으로 거부한다.
> - 이미 발급된 WebSocket one-time ticket은 별도 revoke하지 않는다. TTL이 짧고, 신규 발급은 session token revocation으로 막는다.
> - Redis pub/sub force logout은 best-effort 전달이다. 이벤트를 놓친 gateway도 이후 새 인증은 실패하지만, 이미 열린 연결 즉시 종료는 놓칠 수 있다. 운영상 더 강한 보장이 필요하면 gateway가 주기적으로 user revoke marker를 확인하는 보강이 필요하다.

## 10. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Redis denylist + user revoke-after | HMAC stateless 구조 유지, 즉시 revoke 가능, DB hot path 회피 | Redis 가용성에 인증 경로가 의존 | 선택 |
| DB token version | Redis 유실에 강하고 영속 감사가 쉽다 | user schema 변경과 인증 시 DB/cache 조회가 필요 | 제외 |
| refresh token 체계 도입 | 운영 인증 체계 완성도가 높다 | API, 저장소, rotation 정책 범위가 커져 Phase 8.6을 초과 | 제외 |
| token 단위 denylist만 사용 | 구현이 작다 | suspend 시 사용자의 모든 기존 token을 알 수 없어 강제 로그아웃 요구를 만족하지 못함 | 제외 |
| gateway local close만 사용 | 구현이 단순하다 | admin 앱과 gateway가 분리되면 동작하지 않음 | 제외 |
| Redis pub/sub force logout | 분산 gateway의 local session을 닫을 수 있다 | pub/sub 특성상 missed event 가능성 존재 | 선택 |

## 11. 후속 질문

- Phase 9에서 Redis pub/sub force logout을 더 강하게 보장하려면 stream 기반 control event로 바꿀 것인가?
- HMAC key rotation을 다중 active key id(`kid`) 구조로 확장할 시점을 언제로 둘 것인가?
- suspend 해제 시 사용자가 자동 재로그인되게 할 것인가, 아니면 명시적 재로그인을 계속 요구할 것인가?
