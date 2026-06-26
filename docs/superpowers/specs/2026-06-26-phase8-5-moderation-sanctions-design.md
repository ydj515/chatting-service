# Phase 8.5 Moderation and User Sanctions 설계서

- 작성일: 2026-06-26
- 슬라이스: Phase 8.5 moderation 필터 + 사용자 제재
- 상태: 설계 승인
- 구현 브랜치: `feat/phase8-5-moderation-sanctions`

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 8.5는 Phase 8 운영 토대 위에 abuse 통제를 추가하는 단계다.
- 기존 메시지 수락 경로는 membership, idempotency, admission rate limit/slow mode, sequence 발급, Redis Streams append 순서로 동작한다.
- 기존 `MessageAdmissionPolicyService`는 rate limit과 slow mode를 Redis Lua script로 처리한다.
- 기존 `admin_audit_logs`와 `AdminAuditLogRepository`가 있으므로 admin 변경 이력은 같은 감사 저장소에 기록할 수 있다.
- 금칙어 규칙은 DB 관리형으로 둔다.
- 금칙어 규칙은 `GLOBAL`과 `ROOM` scope를 모두 허용한다.
- `BAN`과 `MUTE`는 1차에서 membership을 끊지 않고 메시지 전송만 차단한다.
- moderation 거부는 rate limit 거부와 구분한다.
- REST API moderation 거부는 `403 Forbidden`으로 응답한다.
- WebSocket moderation 거부는 별도 error code `MESSAGE_MODERATION_REJECTED`로 응답한다.

### 목표

- DB 관리형 금칙어 rule을 추가하고 admin API로 관리한다.
- 메시지 수락 전에 금칙어 rule을 검사해 정책 위반 메시지를 거부한다.
- 방 단위 사용자 제재 `MUTE`, `BAN`을 추가하고 메시지 수락 전에 검사한다.
- 모든 rule/sanction 변경을 admin audit log에 남긴다.
- moderation 거부 metric을 low-cardinality tag로 기록한다.
- rule/sanction 조회를 cache로 감싸 hot path DB 부하를 제한한다.
- sequence 발급과 Redis Streams append 전에 moderation/sanction을 검사해 거부 메시지가 `roomSeq`를 소모하지 않게 한다.

### 비범위

- 자동 AI 기반 콘텐츠 분류는 1차 범위가 아니다.
- review queue, shadow-ban, message masking, hold-and-release workflow는 1차 범위가 아니다.
- `SUSPEND` 전역 계정 제재와 session token revocation은 Phase 8.6에서 닫는다. 8.5에서는 schema 호환과 연결점만 남긴다.
- `subscriber_only` 강제는 구독 도메인이 없으므로 1차 범위에서 제외한다.
- `BAN`으로 membership을 비활성화하지 않는다.
- regex 실행은 ReDoS 위험이 있으므로 1차에서는 `CONTAINS` match만 지원한다.

## 2. 해결 접근

### 선택한 접근

권장안 B를 적용한다.

1. `MessageModerationService`를 추가해 콘텐츠 moderation을 담당한다.
2. `UserSanctionService`를 추가해 `MUTE`/`BAN` 제재를 담당한다.
3. `ChatServiceImpl.sendMessage()`는 idempotency 확인 뒤, admission과 sequence 발급 전에 sanction과 moderation을 검사한다.
4. `moderation_rules` 테이블은 `GLOBAL`과 `ROOM` rule을 저장한다.
5. `user_sanctions` 테이블은 room scoped `MUTE`/`BAN`을 저장한다.
6. `AdminModerationController`를 새로 추가해 기존 `AdminChatController`가 더 커지는 것을 막는다.
7. `AdminChatService`는 moderation API 책임까지 맡기지 않고, 별도 `AdminModerationService`를 둔다.
8. 변경 audit은 기존 `AdminAuditLogRepository`를 재사용한다.
9. moderation 거부는 `MessageModerationRejectedException`으로 분리한다.

### 이유

- rate limit/slow mode와 콘텐츠/제재 정책은 변경 주기와 운영 의미가 다르다.
- Redis Lua admission 경로에 금칙어와 제재를 섞으면 hot path 책임이 커지고 테스트가 어려워진다.
- 별도 service를 두면 `MessageAdmissionPolicyService`는 기존 의미를 유지하고, moderation은 별도 metric, exception, admin API로 발전시킬 수 있다.
- `BAN`을 membership 비활성화와 분리하면 제재 해제/만료 후 사용자가 방 상태를 잃지 않고 즉시 복귀할 수 있다.

## 3. 설계 상세

### DB schema

`moderation_rules`는 admin이 관리하는 금칙어 rule이다.

```sql
CREATE TABLE IF NOT EXISTS moderation_rules (
    id bigserial PRIMARY KEY,
    scope_type varchar(20) NOT NULL,
    room_id bigint,
    pattern text NOT NULL,
    match_type varchar(20) NOT NULL DEFAULT 'CONTAINS',
    action varchar(20) NOT NULL DEFAULT 'REJECT',
    reason varchar(100),
    enabled boolean NOT NULL DEFAULT true,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_moderation_rules_scope CHECK (
        (scope_type = 'GLOBAL' AND room_id IS NULL) OR
        (scope_type = 'ROOM' AND room_id IS NOT NULL)
    ),
    CONSTRAINT ck_moderation_rules_match_type CHECK (match_type IN ('CONTAINS')),
    CONSTRAINT ck_moderation_rules_action CHECK (action IN ('REJECT'))
);
```

`user_sanctions`는 사용자의 메시지 전송 제재를 저장한다.

```sql
CREATE TABLE IF NOT EXISTS user_sanctions (
    id bigserial PRIMARY KEY,
    scope_type varchar(20) NOT NULL,
    room_id bigint,
    user_id bigint NOT NULL,
    type varchar(20) NOT NULL,
    reason text,
    expires_at timestamptz,
    active boolean NOT NULL DEFAULT true,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_by varchar(100),
    revoked_at timestamptz,
    CONSTRAINT ck_user_sanctions_scope CHECK (
        (scope_type = 'ROOM' AND room_id IS NOT NULL) OR
        (scope_type = 'GLOBAL' AND room_id IS NULL)
    ),
    CONSTRAINT ck_user_sanctions_type CHECK (type IN ('MUTE', 'BAN', 'SUSPEND_RESERVED'))
);
```

기본 index는 다음과 같다.

```sql
CREATE INDEX IF NOT EXISTS ix_moderation_rules_active_scope
ON moderation_rules (enabled, scope_type, room_id);

CREATE INDEX IF NOT EXISTS ix_user_sanctions_active_lookup
ON user_sanctions (active, user_id, scope_type, room_id, type, expires_at);

CREATE INDEX IF NOT EXISTS ix_user_sanctions_room_user_created_at
ON user_sanctions (room_id, user_id, created_at DESC);
```

### Moderation rule model

1차 model은 다음 enum을 사용한다.

```kotlin
enum class ModerationScopeType {
    GLOBAL,
    ROOM,
}

enum class ModerationMatchType {
    CONTAINS,
}

enum class ModerationAction {
    REJECT,
}
```

`CONTAINS`는 case-insensitive substring match로 판정한다. Unicode normalization이나 형태소/우회 탐지는 후속 hardening이다.

### User sanction model

1차 model은 다음 enum을 사용한다.

```kotlin
enum class UserSanctionType {
    MUTE,
    BAN,
    SUSPEND_RESERVED,
}
```

`MUTE`와 `BAN`은 모두 메시지 전송을 차단한다. 1차에서 둘의 runtime 효과는 같지만 운영 의미를 분리한다.

- `MUTE`: 방 안에서 말할 수 없는 상태
- `BAN`: 방에서 말할 수 없으며 운영상 더 강한 제재 상태
- `SUSPEND_RESERVED`: Phase 8.6 token revocation과 함께 전역 계정 차단으로 승격할 reserved type

admin API는 8.5에서 `SUSPEND_RESERVED` 생성을 거부한다.

### Runtime flow

`ChatServiceImpl.sendMessage()`는 다음 순서로 동작한다.

1. `clientMessageId`를 정규화한다.
2. 방, 사용자, membership을 확인한다.
3. 같은 `(roomId, senderId, clientMessageId)`로 저장된 기존 메시지가 있으면 그대로 반환한다.
4. `UserSanctionService.requireAllowedToSend(roomId, senderId)`를 호출한다.
5. `MessageModerationService.requireAllowed(roomId, senderId, content, messageType)`를 호출한다.
6. `MessageAdmissionPolicyService.requireAllowed(roomId, senderId, memberRole)`를 호출한다.
7. `messageId`와 `roomSeq`를 생성한다.
8. shard 값을 계산한다.
9. Redis Streams에 append한다.
10. accepted traffic counter를 best-effort로 기록한다.

moderation이나 sanction에서 거부되면 `messageId`, `roomSeq`, stream append가 발생하지 않는다. 또한 moderation/sanction 거부 메시지는 rate limit counter를 소모하지 않는다.

### Error contract

REST API는 `MessageModerationRejectedException`을 `403 Forbidden`으로 매핑한다.

```json
{
  "status": 403,
  "message": "message blocked by moderation policy",
  "path": "/api/chat-rooms/10/messages"
}
```

WebSocket은 다음 error code를 내려보낸다.

```json
{
  "type": "ERROR",
  "errorCode": "MESSAGE_MODERATION_REJECTED",
  "message": "message blocked by moderation policy"
}
```

pattern, rule id, sanction id, user id는 client-facing error에 포함하지 않는다.

### Admin API

`AdminModerationController`는 `/admin/moderation` prefix를 사용한다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/admin/moderation/rules?roomId=&enabled=` | rule 목록 조회 |
| `POST` | `/admin/moderation/rules` | rule 생성 |
| `PATCH` | `/admin/moderation/rules/{ruleId}` | rule 수정 |
| `POST` | `/admin/moderation/rules/{ruleId}/disable` | rule 비활성화 |
| `GET` | `/admin/moderation/sanctions?roomId=&userId=&active=` | sanction 목록 조회 |
| `POST` | `/admin/moderation/sanctions` | sanction 생성 |
| `POST` | `/admin/moderation/sanctions/{sanctionId}/revoke` | sanction 해제 |

rule 생성 request 예시는 다음과 같다.

```json
{
  "scopeType": "ROOM",
  "roomId": 10,
  "pattern": "blocked-word",
  "matchType": "CONTAINS",
  "action": "REJECT",
  "reason": "blocked phrase"
}
```

sanction 생성 request 예시는 다음과 같다.

```json
{
  "scopeType": "ROOM",
  "roomId": 10,
  "userId": 7,
  "type": "MUTE",
  "reason": "spam",
  "expiresAt": "2026-06-26T12:00:00Z"
}
```

### Audit log

기존 `admin_audit_logs`에 다음 action을 기록한다.

- `ADMIN_MODERATION_RULE_CREATED`
- `ADMIN_MODERATION_RULE_UPDATED`
- `ADMIN_MODERATION_RULE_DISABLED`
- `ADMIN_USER_SANCTION_CREATED`
- `ADMIN_USER_SANCTION_REVOKED`

`metadata`에는 request payload와 변경 대상 id를 JSON으로 기록한다. 금칙어 pattern은 admin audit log에 저장한다. 다만 metric tag나 일반 application log에는 pattern을 넣지 않는다.

### Cache

`moderationRules` cache를 추가한다.

- key: `roomId`
- value: `GLOBAL enabled rules + ROOM enabled rules`
- TTL 기본값: `10s`

`userSanctions` cache를 추가한다.

- key: `roomId:userId`
- value: 현재 활성 `MUTE`/`BAN`
- TTL 기본값: `10s`

admin 변경 시 evict 정책은 다음과 같다.

- global rule 생성/수정/비활성화: `moderationRules` 전체 evict
- room rule 생성/수정/비활성화: 해당 `roomId` cache evict
- sanction 생성/해제: 해당 `roomId:userId` cache evict

### Metric

moderation 거부 metric은 다음이다.

| Metric | Type | Tags | 설명 |
| --- | --- | --- | --- |
| `chat.message.moderation.rejected` | Counter | `reason`, `scope`, `action` | moderation/sanction으로 메시지 수락 전 거부된 횟수 |

허용 tag 값은 다음으로 제한한다.

- `reason=blocked_word|muted|banned`
- `scope=global|room`
- `action=reject`

`roomId`, `userId`, `pattern`, `content`, `messageId`, `clientMessageId`는 tag로 쓰지 않는다.

### Verification script

`mise run verify:moderation`은 Phase 8.5 구현 계획에서 추가한다. 1차 script는 backend가 떠 있는 환경에서 다음을 확인한다.

1. admin token 없이 moderation API가 거부된다.
2. admin token으로 global 금칙어 rule을 생성한다.
3. REST 메시지 전송이 `403`으로 거부된다.
4. WebSocket 메시지 전송이 `MESSAGE_MODERATION_REJECTED`로 거부된다.
5. rule 비활성화 후 같은 메시지가 다시 수락된다.

## 4. 구현 계획 요약

세부 implementation plan은 별도 계획 문서에 작성한다. 예상 task 묶음은 다음과 같다.

1. Phase 8.5 spec/plan 문서 작성
2. moderation/sanction domain DTO와 exception 추가
3. PostgreSQL DDL 추가
4. rule/sanction repository와 cache 추가
5. moderation/sanction service 추가
6. `ChatServiceImpl` 수락 전 gate 연결
7. REST/WebSocket error contract 추가
8. admin moderation API와 audit log 연결
9. metric과 cache evict 테스트 추가
10. `verify:moderation` 스크립트와 문서 추가
11. Kotlin test와 문법 검증 실행

## 5. 복잡도

- 금칙어 검사 시간 복잡도: `O(R * L)`
  - `R`은 적용 가능한 active rule 수다.
  - `L`은 메시지 content 길이다.
- sanction 검사 시간 복잡도: cache hit 기준 `O(1)`, cache miss 기준 `O(log S + K)`
  - `S`는 sanction row 수다.
  - `K`는 조회된 active sanction 수다.
- rule list 조회 시간 복잡도: `O(log R + K)`
- sanction list 조회 시간 복잡도: `O(log S + K)`
- 메시지 수락 경로 추가 공간 복잡도: cache hit 기준 `O(R + A)`
  - `A`는 한 사용자에게 적용되는 active sanction 수다.
- 전체 cache 공간 복잡도: active rule과 active sanction cache entry 기준 `O(R + S)`

## 6. 주의사항

> - 금칙어 pattern 원문을 client-facing error, metric tag, 일반 application log에 노출하지 않는다.
> - `GLOBAL` rule 변경은 모든 방의 moderation 결과에 영향을 준다. cache 전체 evict가 필요하다.
> - `BAN`은 membership을 끊지 않는다. 사용자는 방에는 남아 있지만 메시지를 보낼 수 없는 상태가 된다.
> - `SUSPEND_RESERVED`는 Phase 8.6 token revocation과 함께 처리해야 보안 의미가 완성된다.
> - regex 실행은 ReDoS 위험이 있으므로 1차에서 제외한다.
> - moderation을 admission보다 먼저 실행하므로 차단된 메시지는 rate limit counter를 소모하지 않는다. 이는 의도된 정책이다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 기존 `MessageAdmissionPolicyService`에 moderation 통합 | 호출 지점이 하나라 구현이 빠르다 | Redis Lua admission과 콘텐츠/제재 정책이 섞여 책임이 커진다 | 제외 |
| 별도 `MessageModerationService`와 `UserSanctionService` | 책임이 분리되고 테스트, metric, admin API가 명확하다 | 메시지 hot path에 service 호출이 추가된다 | 선택 |
| admin API만 먼저 만들고 수락 경로 적용은 후속 | DB 관리 화면/API를 작게 시작할 수 있다 | Phase 8.5 완료 기준인 수락 전 거부를 만족하지 못한다 | 제외 |
| room rule만 허용 | cache invalidation이 단순하다 | 공통 금칙어를 방마다 반복 등록해야 한다 | 제외 |
| global + room rule 허용 | 운영 효율이 좋고 공통 정책을 한 번에 적용할 수 있다 | global rule 변경 시 cache 영향 범위가 크다 | 선택 |
| `BAN` 시 membership 비활성화 | 강한 제재 의미가 분명하다 | 재입장/복구 정책과 UX 영향이 커진다 | 제외 |
| `BAN` 시 메시지 전송만 차단 | 해제/만료 후 즉시 복귀 가능하고 1차 범위가 작다 | 방에는 남아 있지만 말할 수 없는 상태를 UI/API가 이해해야 한다 | 선택 |

## 8. 후속 질문

- Phase 8.6에서 `SUSPEND_RESERVED`를 실제 global suspend와 token revocation으로 승격할 때 session token denylist를 Redis로 둘 것인가?
- `CONTAINS` 기반 금칙어 우회를 줄이기 위해 Unicode normalization과 whitespace folding을 다음 moderation hardening에 포함할 것인가?
- moderation rule이 많아질 경우 Aho-Corasick 같은 multi-pattern matcher로 전환할 기준을 어떤 metric으로 잡을 것인가?
