# Phase 8.6 Token Revocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Redis-backed session token revocation, `GLOBAL + SUSPEND` sanctions, and distributed WebSocket force logout.

**Architecture:** Keep the existing HMAC session token shape, but add Redis revocation checks inside `HmacSessionTokenService`. Model revocation as a domain port with Redis implementation, and model WebSocket force logout as a separate Redis control broker so it does not expand the existing room message broker responsibility.

**Tech Stack:** Kotlin, Spring Boot, Spring Data Redis `RedisTemplate<String, String>`, JUnit 5, Mockito, Gradle.

---

## File Structure

- Create `chat-domain/src/main/kotlin/service/SessionControlPublisher.kt`
  - Domain port for publishing force logout control events.
- Create `chat-domain/src/main/kotlin/service/SessionTokenRevocationStore.kt`
  - Domain port for token and user-wide revocation markers.
- Modify `chat-domain/src/main/kotlin/service/SessionTokenService.kt`
  - Add `revokeToken(token)` and `revokeUserTokens(userId)`.
- Modify `chat-domain/src/main/kotlin/dto/AdminModerationDto.kt`
  - Replace `SUSPEND_RESERVED` with `SUSPEND`.
- Modify `chat-persistence/src/main/kotlin/config/ChatAuthProperties.kt`
  - Add revocation and session control Redis settings.
- Create `chat-persistence/src/main/kotlin/service/RedisSessionTokenRevocationStore.kt`
  - Redis implementation for token hash denylist and user revoke-after marker.
- Modify `chat-persistence/src/main/kotlin/service/HmacSessionTokenService.kt`
  - Add issued-at token claims, revocation checks, and revoke methods.
- Create `chat-persistence/src/main/kotlin/service/RedisSessionControlBroker.kt`
  - Redis pub/sub broker for force logout events.
- Modify `chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt`
  - Subscribe to force logout events and close local sessions for a user.
- Modify `chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt`
  - Permit `GLOBAL + SUSPEND`, revoke user tokens, publish force logout.
- Modify `chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt`
  - Add active global sanctions lookup if implementation needs direct suspend checks.
- Modify `infra/postgres/message-partitions.sql`
  - Allow `SUSPEND` in `ck_user_sanctions_type`.
- Modify `chat-api/src/main/kotlin/controller/UserController.kt`
  - Add `POST /logout`.
- Add or update tests in matching `src/test/kotlin` paths.

## Task 1: Domain Ports and DTO Contract

**Files:**
- Create: `chat-domain/src/main/kotlin/service/SessionTokenRevocationStore.kt`
- Create: `chat-domain/src/main/kotlin/service/SessionControlPublisher.kt`
- Modify: `chat-domain/src/main/kotlin/service/SessionTokenService.kt`
- Modify: `chat-domain/src/main/kotlin/dto/AdminModerationDto.kt`
- Test: `chat-domain/src/test/kotlin/dto/AdminModerationDtoTest.kt`

- [ ] **Step 1: Write the failing enum contract test**

Add this test to `chat-domain/src/test/kotlin/dto/AdminModerationDtoTest.kt`:

```kotlin
@Test
fun `user sanction type은 suspend를 실제 전역 제재 타입으로 제공한다`() {
    assertEquals(UserSanctionType.SUSPEND, UserSanctionType.valueOf("SUSPEND"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :chat-domain:test --tests com.chat.domain.dto.AdminModerationDtoTest --no-daemon
```

Expected: FAIL because `UserSanctionType.SUSPEND` does not exist.

- [ ] **Step 3: Add domain ports and service methods**

Create `chat-domain/src/main/kotlin/service/SessionTokenRevocationStore.kt`:

```kotlin
package com.chat.domain.service

import java.time.Instant

interface SessionTokenRevocationStore {
    fun revokeToken(token: String, expiresAt: Instant)
    fun revokeUserTokens(userId: Long, revokedAt: Instant)
    fun isTokenRevoked(token: String): Boolean
    fun userRevokedAt(userId: Long): Instant?
}
```

Create `chat-domain/src/main/kotlin/service/SessionControlPublisher.kt`:

```kotlin
package com.chat.domain.service

interface SessionControlPublisher {
    fun forceLogoutUser(userId: Long, reason: String)

    object Noop : SessionControlPublisher {
        override fun forceLogoutUser(userId: Long, reason: String) = Unit
    }
}
```

Modify `chat-domain/src/main/kotlin/service/SessionTokenService.kt`:

```kotlin
interface SessionTokenService {
    fun issueToken(userId: Long): SessionToken
    fun authenticate(token: String): AuthenticatedSession?
    fun revokeToken(token: String): Boolean
    fun revokeUserTokens(userId: Long)
}
```

Modify `UserSanctionType` in `chat-domain/src/main/kotlin/dto/AdminModerationDto.kt`:

```kotlin
enum class UserSanctionType {
    MUTE,
    BAN,
    SUSPEND,
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :chat-domain:test --tests com.chat.domain.dto.AdminModerationDtoTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-domain/src/main/kotlin/service/SessionTokenRevocationStore.kt \
  chat-domain/src/main/kotlin/service/SessionControlPublisher.kt \
  chat-domain/src/main/kotlin/service/SessionTokenService.kt \
  chat-domain/src/main/kotlin/dto/AdminModerationDto.kt \
  chat-domain/src/test/kotlin/dto/AdminModerationDtoTest.kt
git commit -m "feat: add session revocation domain contracts"
```

## Task 2: Redis Revocation Store

**Files:**
- Modify: `chat-persistence/src/main/kotlin/config/ChatAuthProperties.kt`
- Create: `chat-persistence/src/main/kotlin/service/RedisSessionTokenRevocationStore.kt`
- Test: `chat-persistence/src/test/kotlin/service/RedisSessionTokenRevocationStoreTest.kt`

- [ ] **Step 1: Write failing revocation store tests**

Create `chat-persistence/src/test/kotlin/service/RedisSessionTokenRevocationStoreTest.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.persistence.config.ChatAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RedisSessionTokenRevocationStoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `token revoke는 원문 token 대신 hash key를 저장하고 만료까지 ttl을 건다`() {
        val redisTemplate = mockRedisTemplate()
        val store = store(redisTemplate.template)

        store.revokeToken("plain-session-token", clock.instant().plusSeconds(60))

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(redisTemplate.valueOps).set(keyCaptor.capture(), eq("1"), eq(Duration.ofSeconds(60)))
        assertFalse(keyCaptor.value.contains("plain-session-token"))
        assertTrue(keyCaptor.value.startsWith("chat:auth:session:revoked:token:"))
    }

    @Test
    fun `user revoke marker는 epoch second를 저장하고 다시 읽는다`() {
        val redisTemplate = mockRedisTemplate()
        val store = store(redisTemplate.template)

        store.revokeUserTokens(7L, clock.instant())
        verify(redisTemplate.valueOps).set(
            eq("chat:auth:session:revoked:user:7"),
            eq("1782518400"),
            eq(Duration.ofHours(13)),
        )

        `when`(redisTemplate.valueOps.get("chat:auth:session:revoked:user:7")).thenReturn("1782518400")
        assertEquals(clock.instant(), store.userRevokedAt(7L))
    }

    private fun store(redisTemplate: RedisTemplate<String, String>) = RedisSessionTokenRevocationStore(
        redisTemplate = redisTemplate,
        authProperties = ChatAuthProperties(
            session = ChatAuthProperties.Session(
                secret = "test-secret",
                ttl = Duration.ofHours(12),
                userRevocationGraceTtl = Duration.ofHours(1),
            )
        ),
        clock = clock,
    )

    private data class MockRedis(
        val template: RedisTemplate<String, String>,
        val valueOps: ValueOperations<String, String>,
    )

    private fun mockRedisTemplate(): MockRedis {
        val template = mock(RedisTemplate::class.java) as RedisTemplate<String, String>
        val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOps)
        return MockRedis(template, valueOps)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.RedisSessionTokenRevocationStoreTest --no-daemon
```

Expected: FAIL because `RedisSessionTokenRevocationStore` and new properties do not exist.

- [ ] **Step 3: Implement Redis store and properties**

Add fields to `ChatAuthProperties.Session`:

```kotlin
val revocationKeyPrefix: String = "chat:auth:session:revoked:",
val userRevocationGraceTtl: Duration = Duration.ofHours(1),
val controlTopic: String = "chat.session.control",
```

Create `chat-persistence/src/main/kotlin/service/RedisSessionTokenRevocationStore.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.persistence.config.ChatAuthProperties
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Service
class RedisSessionTokenRevocationStore(
    private val redisTemplate: RedisTemplate<String, String>,
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
) : SessionTokenRevocationStore {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun revokeToken(token: String, expiresAt: Instant) {
        val ttl = Duration.between(clock.instant(), expiresAt)
        if (!ttl.isPositive) {
            return
        }
        redisTemplate.opsForValue().set(tokenKey(token), "1", ttl)
    }

    override fun revokeUserTokens(userId: Long, revokedAt: Instant) {
        redisTemplate.opsForValue().set(
            userKey(userId),
            revokedAt.epochSecond.toString(),
            authProperties.session.ttl.plus(authProperties.session.userRevocationGraceTtl),
        )
    }

    override fun isTokenRevoked(token: String): Boolean {
        return redisTemplate.opsForValue().get(tokenKey(token)) != null
    }

    override fun userRevokedAt(userId: Long): Instant? {
        return redisTemplate.opsForValue().get(userKey(userId))
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it) }
    }

    private fun tokenKey(token: String): String {
        return "${authProperties.session.revocationKeyPrefix}token:${hash(token)}"
    }

    private fun userKey(userId: Long): String {
        return "${authProperties.session.revocationKeyPrefix}user:$userId"
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return encoder.encodeToString(digest)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.RedisSessionTokenRevocationStoreTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-persistence/src/main/kotlin/config/ChatAuthProperties.kt \
  chat-persistence/src/main/kotlin/service/RedisSessionTokenRevocationStore.kt \
  chat-persistence/src/test/kotlin/service/RedisSessionTokenRevocationStoreTest.kt
git commit -m "feat: add redis session token revocation store"
```

## Task 3: HMAC Token Revocation Checks

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/HmacSessionTokenService.kt`
- Modify: `chat-persistence/src/test/kotlin/service/HmacSessionTokenServiceTest.kt`

- [ ] **Step 1: Write failing token service tests**

Add tests to `HmacSessionTokenServiceTest`:

```kotlin
@Test
fun `revoke된 session token은 인증하지 않는다`() {
    val revocationStore = InMemoryRevocationStore()
    val service = HmacSessionTokenService(properties, clock, revocationStore)
    val issued = service.issueToken(42L)

    assertTrue(service.revokeToken(issued.token))

    assertNull(service.authenticate(issued.token))
}

@Test
fun `user revoke 이후 기존 token은 인증하지 않고 이후 발급 token은 인증한다`() {
    val revocationStore = InMemoryRevocationStore()
    val service = HmacSessionTokenService(properties, clock, revocationStore)
    val oldToken = service.issueToken(42L)

    service.revokeUserTokens(42L)

    assertNull(service.authenticate(oldToken.token))

    val laterClock = Clock.fixed(clock.instant().plusSeconds(1), ZoneOffset.UTC)
    val laterService = HmacSessionTokenService(properties, laterClock, revocationStore)
    val newToken = laterService.issueToken(42L)

    assertEquals(42L, laterService.authenticate(newToken.token)?.userId)
}
```

Add this fake store to the test file:

```kotlin
private class InMemoryRevocationStore : SessionTokenRevocationStore {
    private val revokedTokens = mutableSetOf<String>()
    private val revokedUsers = mutableMapOf<Long, Instant>()

    override fun revokeToken(token: String, expiresAt: Instant) {
        revokedTokens += token
    }

    override fun revokeUserTokens(userId: Long, revokedAt: Instant) {
        revokedUsers[userId] = revokedAt
    }

    override fun isTokenRevoked(token: String): Boolean = token in revokedTokens
    override fun userRevokedAt(userId: Long): Instant? = revokedUsers[userId]
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.HmacSessionTokenServiceTest --no-daemon
```

Expected: FAIL because constructor and revoke methods do not match.

- [ ] **Step 3: Implement claim parsing and revocation checks**

Update `HmacSessionTokenService` constructor:

```kotlin
class HmacSessionTokenService(
    private val authProperties: ChatAuthProperties,
    private val clock: Clock,
    private val revocationStore: SessionTokenRevocationStore,
) : SessionTokenService
```

Add nullable fallback bean only if needed by tests through explicit fake stores. Production has Redis implementation.

Issue payload:

```kotlin
val issuedAtInstant = clock.instant()
val expiresAtInstant = issuedAtInstant.plus(authProperties.session.ttl)
val payload = listOf(
    userId.toString(),
    issuedAtInstant.epochSecond.toString(),
    expiresAtInstant.epochSecond.toString(),
    UUID.randomUUID().toString().replace("-", ""),
).joinToString(":")
```

Use a private claim type:

```kotlin
private data class TokenClaims(
    val userId: Long,
    val issuedAt: Instant?,
    val expiresAt: Instant,
)
```

Apply checks:

```kotlin
if (revocationStore.isTokenRevoked(token)) return null
val userRevokedAt = revocationStore.userRevokedAt(userId)
if (userRevokedAt != null) {
    if (issuedAt == null || !issuedAt.isAfter(userRevokedAt)) {
        return null
    }
}
```

Implement service methods:

```kotlin
override fun revokeToken(token: String): Boolean {
    val claims = parseAndVerify(token) ?: return false
    if (!claims.expiresAt.isAfter(clock.instant())) return false
    revocationStore.revokeToken(token, claims.expiresAt)
    return true
}

override fun revokeUserTokens(userId: Long) {
    revocationStore.revokeUserTokens(userId, clock.instant())
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.HmacSessionTokenServiceTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/HmacSessionTokenService.kt \
  chat-persistence/src/test/kotlin/service/HmacSessionTokenServiceTest.kt
git commit -m "feat: enforce session token revocation"
```

## Task 4: Logout Endpoint

**Files:**
- Modify: `chat-api/src/main/kotlin/controller/UserController.kt`
- Modify: `chat-api/src/test/kotlin/controller/UserControllerTest.kt`

- [ ] **Step 1: Write failing logout controller test**

Add a test that posts to `/logout` with `Authorization: Bearer session-token`, expects `204`, and verifies `sessionTokenService.revokeToken("session-token")`.

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :chat-api:test --tests com.chat.api.controller.UserControllerTest --no-daemon
```

Expected: FAIL because `/logout` does not exist.

- [ ] **Step 3: Implement endpoint**

Add to `UserController`:

```kotlin
@PostMapping("/logout")
fun logout(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorizationHeader: String?): ResponseEntity<Void> {
    val token = authorizationHeader
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("인증 토큰이 필요합니다.")

    userService.logout(token)
    return ResponseEntity.noContent().build()
}
```

If `UserService` does not expose logout, add `fun logout(sessionToken: String)` and delegate to `SessionTokenService.revokeToken(sessionToken)`.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :chat-api:test --tests com.chat.api.controller.UserControllerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-domain/src/main/kotlin/service/UserService.kt \
  chat-persistence/src/main/kotlin/service/UserServiceImpl.kt \
  chat-api/src/main/kotlin/controller/UserController.kt \
  chat-api/src/test/kotlin/controller/UserControllerTest.kt
git commit -m "feat: add session logout endpoint"
```

## Task 5: Distributed Session Control Broker

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/RedisSessionControlBroker.kt`
- Modify: `chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt`
- Test: `chat-persistence/src/test/kotlin/service/RedisSessionControlBrokerTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/WebSocketSessionManagerTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests for:

- `RedisSessionControlBroker.forceLogoutUser()` publishes JSON to `chat.session.control`.
- `WebSocketSessionManager.closeSessionsForUser()` closes only sessions for the target user and removes indexes.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.RedisSessionControlBrokerTest --tests com.chat.persistence.service.WebSocketSessionManagerTest --no-daemon
```

Expected: FAIL because broker and close method do not exist.

- [ ] **Step 3: Implement broker and session closing**

Create `RedisSessionControlBroker` as a `MessageListener` that subscribes to `authProperties.session.controlTopic` at `@PostConstruct`, publishes `FORCE_LOGOUT_USER`, and invokes a local handler for remote events.

Add to `WebSocketSessionManager.initialize()`:

```kotlin
sessionControlBroker.setLocalForceLogoutHandler { userId, reason ->
    closeSessionsForUser(userId, CloseStatus(4003, "Session revoked"))
}
```

Add:

```kotlin
fun closeSessionsForUser(userId: Long, closeStatus: CloseStatus = SESSION_REVOKED_STATUS) {
    openSessionRefsForUser(userId).forEach { sessionRef ->
        closeSession(sessionRef.session, closeStatus)
        removeSession(sessionRef.userId, sessionRef.session)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.RedisSessionControlBrokerTest --tests com.chat.persistence.service.WebSocketSessionManagerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/RedisSessionControlBroker.kt \
  chat-persistence/src/main/kotlin/service/WebSocketSessionManager.kt \
  chat-persistence/src/test/kotlin/service/RedisSessionControlBrokerTest.kt \
  chat-persistence/src/test/kotlin/service/WebSocketSessionManagerTest.kt
git commit -m "feat: add distributed session force logout"
```

## Task 6: GLOBAL SUSPEND Sanctions

**Files:**
- Modify: `infra/postgres/message-partitions.sql`
- Modify: `chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt`
- Modify: `chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt`
- Modify: `chat-persistence/src/main/kotlin/service/UserSanctionService.kt`
- Modify: `chat-persistence/src/test/kotlin/service/AdminModerationServiceImplTest.kt`
- Modify: `chat-persistence/src/test/kotlin/repository/UserSanctionJdbcRepositoryTest.kt`
- Modify: `chat-persistence/src/test/kotlin/service/UserSanctionServiceTest.kt`

- [ ] **Step 1: Write failing sanction tests**

Add tests:

- `GLOBAL + SUSPEND` is accepted.
- `GLOBAL + SUSPEND` revokes user tokens and publishes force logout.
- `ROOM + SUSPEND` is rejected.
- `GLOBAL + MUTE` is rejected.
- `UserSanctionService` treats `SUSPEND` as a send blocker if a global suspend record is present.
- `UserSanctionJdbcRepository.activeGlobalSanctionsForUser(userId)` queries `active = true`, `scope_type = 'GLOBAL'`, and `room_id IS NULL`.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminModerationServiceImplTest --tests com.chat.persistence.service.UserSanctionServiceTest --no-daemon
```

Expected: FAIL because `SUSPEND` policy is not implemented.

- [ ] **Step 3: Implement SUSPEND policy**

In `AdminModerationServiceImpl.validateSanctionRequest()`:

```kotlin
when (request.type) {
    UserSanctionType.MUTE, UserSanctionType.BAN -> {
        if (request.scopeType != ModerationScopeType.ROOM || request.roomId == null) {
            throw IllegalArgumentException("MUTE and BAN require ROOM scope")
        }
    }
    UserSanctionType.SUSPEND -> {
        if (request.scopeType != ModerationScopeType.GLOBAL || request.roomId != null) {
            throw IllegalArgumentException("SUSPEND requires GLOBAL scope")
        }
    }
}
```

After create/audit, for `SUSPEND` call:

```kotlin
sessionTokenService.revokeUserTokens(record.userId)
sessionControlPublisher.forceLogoutUser(record.userId, "suspended")
```

Update SQL constraint:

```sql
CONSTRAINT ck_user_sanctions_type CHECK (type IN ('MUTE', 'BAN', 'SUSPEND'))
```

Add global lookup to `UserSanctionJdbcRepository`:

```kotlin
@Cacheable(
    value = ["userSanctions"],
    key = "'global:' + #userId",
)
fun activeGlobalSanctionsForUser(userId: Long): List<UserSanctionRecord> {
    return jdbcTemplate.query(
        """
        SELECT id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
        FROM user_sanctions
        WHERE active = true
          AND user_id = ?
          AND scope_type = 'GLOBAL'
          AND room_id IS NULL
        ORDER BY id DESC
        """.trimIndent(),
        ROW_MAPPER,
        userId,
    )
}
```

Update `UserSanctionService.requireAllowedToSend()` to merge global and room sanctions before expiry/type filtering:

```kotlin
val now = clock.instant()
val sanctions = userSanctionRepository.activeGlobalSanctionsForUser(userId) +
    userSanctionRepository.activeSanctionsForUser(roomId, userId)
val sanction = sanctions
    .asSequence()
    .filter { sanction -> sanction.expiresAt == null || sanction.expiresAt.isAfter(now) }
    .firstOrNull { sanction ->
        sanction.type == UserSanctionType.SUSPEND ||
            sanction.type == UserSanctionType.MUTE ||
            sanction.type == UserSanctionType.BAN
    }
    ?: return
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminModerationServiceImplTest --tests com.chat.persistence.service.UserSanctionServiceTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/postgres/message-partitions.sql \
  chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt \
  chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt \
  chat-persistence/src/main/kotlin/service/UserSanctionService.kt \
  chat-persistence/src/test/kotlin/repository/UserSanctionJdbcRepositoryTest.kt \
  chat-persistence/src/test/kotlin/service/AdminModerationServiceImplTest.kt \
  chat-persistence/src/test/kotlin/service/UserSanctionServiceTest.kt
git commit -m "feat: enforce global suspend sanctions"
```

## Task 7: Configuration and Documentation

**Files:**
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Modify: `docs/configuration.md`
- Modify: `docs/superpowers/specs/2026-06-26-phase8-5-moderation-sanctions-design.md`

- [ ] **Step 1: Add configuration docs**

Add these entries to `docs/configuration.md`:

```markdown
| `CHAT_AUTH_SESSION_REVOCATION_KEY_PREFIX` | `chat:auth:session:revoked:` | Redis session revocation key prefix |
| `CHAT_AUTH_SESSION_USER_REVOCATION_GRACE_TTL` | `1h` | User-wide revocation marker retention after session TTL |
| `CHAT_AUTH_SESSION_CONTROL_TOPIC` | `chat.session.control` | Redis pub/sub topic for distributed force logout |
```

- [ ] **Step 2: Add runtime config**

Add to `application-docker.yml` under `chat.auth.session`:

```yaml
revocation-key-prefix: ${CHAT_AUTH_SESSION_REVOCATION_KEY_PREFIX:chat:auth:session:revoked:}
user-revocation-grace-ttl: ${CHAT_AUTH_SESSION_USER_REVOCATION_GRACE_TTL:1h}
control-topic: ${CHAT_AUTH_SESSION_CONTROL_TOPIC:chat.session.control}
```

- [ ] **Step 3: Update Phase 8.5 follow-up text**

Update `docs/superpowers/specs/2026-06-26-phase8-5-moderation-sanctions-design.md`:

- Change the `SUSPEND_RESERVED` enum example to `SUSPEND`.
- Change the note that admin API rejects `SUSPEND_RESERVED` to say Phase 8.5 rejected global suspend until Phase 8.6.
- Change the follow-up question about promoting `SUSPEND_RESERVED` to say Phase 8.6 implements `GLOBAL + SUSPEND` with token revocation.

- [ ] **Step 4: Run doc/config smoke checks**

Run:

```bash
./gradlew :chat-runtime-config:processResources --no-daemon
```

Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add chat-runtime-config/src/main/resources/application-docker.yml \
  docs/configuration.md \
  docs/superpowers/specs/2026-06-26-phase8-5-moderation-sanctions-design.md
git commit -m "docs: document session revocation configuration"
```

## Task 8: Final Verification

**Files:**
- All touched files.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :chat-domain:test :chat-persistence:test :chat-api:test --no-daemon
```

Expected: SUCCESS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test --no-daemon
```

Expected: SUCCESS.

- [ ] **Step 3: Run moderation smoke verification**

Run:

```bash
mise run verify:moderation
```

Expected: SUCCESS. If this requires services not running, report the exact missing dependency and run `node --check scripts/verify-moderation.mjs` as a syntax fallback.

- [ ] **Step 4: Check diff hygiene**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: no whitespace errors, branch ahead with clean or intentionally staged state.

- [ ] **Step 5: Final commit if verification/doc fixes remain**

```bash
git add docs/configuration.md docs/superpowers/specs/2026-06-26-phase8-5-moderation-sanctions-design.md
git commit -m "test: verify phase 8.6 token revocation"
```

## Self-Review

- Spec coverage: Redis denylist, user revoke-after, `GLOBAL + SUSPEND`, logout endpoint, WebSocket force logout, config, and tests are covered by Tasks 1-8.
- Placeholder scan: no placeholder markers or vague "write tests" steps remain; each task has exact paths and commands.
- Type consistency: port names are `SessionTokenRevocationStore` and `SessionControlPublisher`; Redis implementation names are `RedisSessionTokenRevocationStore` and `RedisSessionControlBroker`.
