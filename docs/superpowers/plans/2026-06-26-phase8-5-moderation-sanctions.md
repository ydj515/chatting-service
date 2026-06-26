# Phase 8.5 Moderation and User Sanctions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DB 관리형 금칙어 rule과 방 단위 `MUTE`/`BAN` 제재를 메시지 수락 전 gate로 적용하고, admin API, audit log, metric, cache, REST/WebSocket 거부 계약을 함께 추가한다.

**Architecture:** `MessageModerationService`와 `UserSanctionService`를 기존 Redis admission 경로와 분리한다. `ChatServiceImpl.sendMessage()`는 idempotency 확인 뒤 sequence 발급 전에 sanction, moderation, admission 순서로 검사한다. Admin 변경 경로는 별도 `AdminModerationController`와 `AdminModerationService`를 사용하고 기존 `AdminAuditLogRepository`에 변경 이력을 남긴다.

**Tech Stack:** Kotlin, Spring Boot MVC, Spring Cache, JdbcTemplate, Micrometer, PostgreSQL DDL, JUnit 5, Mockito, MockMvc, Node.js verification script.

---

## File Structure

- Create `chat-domain/src/main/kotlin/exception/MessageModerationRejectedException.kt`: moderation/sanction 거부용 domain exception.
- Create `chat-domain/src/main/kotlin/dto/AdminModerationDto.kt`: admin moderation API request/response DTO와 enum.
- Modify `chat-domain/src/main/kotlin/service/ChatService.kt`: 변경 없음. 기존 `sendMessage` contract만 사용한다.
- Create `chat-domain/src/main/kotlin/service/AdminModerationService.kt`: admin moderation service interface.
- Modify `chat-domain/src/main/kotlin/dto/WebSocketDto.kt`: 새 WebSocket error code 문자열은 enum이 없으므로 DTO 변경은 하지 않는다. handler test에서 `"MESSAGE_MODERATION_REJECTED"` 문자열을 검증한다.
- Modify `infra/postgres/message-partitions.sql`: `moderation_rules`, `user_sanctions` 테이블과 index 추가.
- Modify `chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt`: `moderationRulesTtl`, `userSanctionsTtl` 추가.
- Modify `chat-persistence/src/main/kotlin/config/CacheConfig.kt`: `moderationRules`, `userSanctions` cache 등록.
- Create `chat-persistence/src/main/kotlin/repository/ModerationRuleJdbcRepository.kt`: DB 관리형 rule 조회와 admin CRUD.
- Create `chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt`: active sanction 조회와 admin CRUD.
- Create `chat-persistence/src/main/kotlin/service/MessageModerationService.kt`: content rule 검사와 rejection metric.
- Create `chat-persistence/src/main/kotlin/service/UserSanctionService.kt`: active `MUTE`/`BAN` 검사와 rejection metric.
- Modify `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`: sanction/moderation gate 주입 및 호출.
- Create `chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt`: admin rule/sanction 변경, cache evict, audit log 기록.
- Modify `chat-api/src/main/kotlin/controller/GlobalExceptionHandler.kt`: `MessageModerationRejectedException`을 `403 Forbidden`으로 매핑.
- Modify `chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt`: moderation exception을 `MESSAGE_MODERATION_REJECTED` error로 전송.
- Create `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminModerationController.kt`: moderation admin API.
- Create `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminModerationControllerTest.kt`: admin API validation/controller tests.
- Create `chat-persistence/src/test/kotlin/repository/ModerationRuleJdbcRepositoryTest.kt`: rule SQL binding/cache tests.
- Create `chat-persistence/src/test/kotlin/repository/UserSanctionJdbcRepositoryTest.kt`: sanction SQL binding/cache tests.
- Create `chat-persistence/src/test/kotlin/service/MessageModerationServiceTest.kt`: global/room contains matching, metric, no-op tests.
- Create `chat-persistence/src/test/kotlin/service/UserSanctionServiceTest.kt`: mute/ban/expired/revoked tests.
- Modify `chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt`: moderation/sanction 거부 시 sequence/stream 미수행 tests.
- Create `chat-persistence/src/test/kotlin/service/AdminModerationServiceImplTest.kt`: audit/cache evict transaction tests.
- Modify `chat-api/src/test/kotlin/controller/GlobalExceptionHandlerTest.kt`: REST `403` test.
- Modify `chat-websocket/src/test/kotlin/handler/ChatWebSocketHandlerTest.kt`: WebSocket moderation error test.
- Modify `chat-persistence/src/test/kotlin/config/CacheConfigTest.kt`: serializer와 cache property coverage.
- Create `scripts/verify-moderation.mjs`: running stack verification script.
- Modify `mise.toml`: `verify:moderation` task 추가.
- Modify `README.md`, `docs/configuration.md`, `docs/observability_metrics.md`: 운영 문서 갱신.

---

### Task 1: Domain DTOs and Exception

**Files:**
- Create: `chat-domain/src/main/kotlin/exception/MessageModerationRejectedException.kt`
- Create: `chat-domain/src/main/kotlin/dto/AdminModerationDto.kt`
- Create: `chat-domain/src/main/kotlin/service/AdminModerationService.kt`
- Test: `./gradlew :chat-domain:test --no-daemon`

- [ ] **Step 1: Create the moderation exception**

Create `chat-domain/src/main/kotlin/exception/MessageModerationRejectedException.kt`:

```kotlin
package com.chat.domain.exception

class MessageModerationRejectedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

- [ ] **Step 2: Create admin moderation DTOs**

Create `chat-domain/src/main/kotlin/dto/AdminModerationDto.kt`:

```kotlin
package com.chat.domain.dto

import java.time.Instant

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

enum class UserSanctionType {
    MUTE,
    BAN,
    SUSPEND_RESERVED,
}

data class AdminModerationRuleDto(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val pattern: String,
    val matchType: ModerationMatchType,
    val action: ModerationAction,
    val reason: String?,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminCreateModerationRuleRequest(
    val scopeType: ModerationScopeType,
    val roomId: Long? = null,
    val pattern: String,
    val matchType: ModerationMatchType = ModerationMatchType.CONTAINS,
    val action: ModerationAction = ModerationAction.REJECT,
    val reason: String? = null,
)

data class AdminUpdateModerationRuleRequest(
    val pattern: String? = null,
    val reason: String? = null,
    val enabled: Boolean? = null,
)

data class AdminUserSanctionDto(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val userId: Long,
    val type: UserSanctionType,
    val reason: String?,
    val expiresAt: Instant?,
    val active: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val revokedBy: String?,
    val revokedAt: Instant?,
)

data class AdminCreateUserSanctionRequest(
    val scopeType: ModerationScopeType,
    val roomId: Long? = null,
    val userId: Long,
    val type: UserSanctionType,
    val reason: String? = null,
    val expiresAt: Instant? = null,
)
```

- [ ] **Step 3: Create the admin moderation service contract**

Create `chat-domain/src/main/kotlin/service/AdminModerationService.kt`:

```kotlin
package com.chat.domain.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto

interface AdminModerationService {
    fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto>

    fun createRule(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto

    fun updateRule(actor: String, ruleId: Long, request: AdminUpdateModerationRuleRequest): AdminModerationRuleDto

    fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto

    fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto>

    fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto

    fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto
}
```

- [ ] **Step 4: Run domain tests**

Run:

```bash
./gradlew :chat-domain:test --no-daemon
```

Expected: compile succeeds and domain tests pass.

- [ ] **Step 5: Commit**

```bash
git add chat-domain/src/main/kotlin/exception/MessageModerationRejectedException.kt \
  chat-domain/src/main/kotlin/dto/AdminModerationDto.kt \
  chat-domain/src/main/kotlin/service/AdminModerationService.kt
git commit -m "feat: add moderation domain contract"
```

---

### Task 2: DDL, Cache Properties, and Repository Contracts

**Files:**
- Modify: `infra/postgres/message-partitions.sql`
- Modify: `chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/config/CacheConfig.kt`
- Create: `chat-persistence/src/main/kotlin/repository/ModerationRuleJdbcRepository.kt`
- Create: `chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt`
- Test: `chat-persistence/src/test/kotlin/repository/ModerationRuleJdbcRepositoryTest.kt`
- Test: `chat-persistence/src/test/kotlin/repository/UserSanctionJdbcRepositoryTest.kt`
- Test: `chat-persistence/src/test/kotlin/config/CacheConfigTest.kt`

- [ ] **Step 1: Write repository tests for active rule lookup**

Create `chat-persistence/src/test/kotlin/repository/ModerationRuleJdbcRepositoryTest.kt` with these first tests:

```kotlin
package com.chat.persistence.repository

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

class ModerationRuleJdbcRepositoryTest {

    @Test
    fun `activeRulesForRoom은 global과 room rule을 함께 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = ModerationRuleJdbcRepository(jdbcTemplate)

        `when`(
            jdbcTemplate.query(
                any(String::class.java),
                any<org.springframework.jdbc.core.RowMapper<ModerationRuleRecord>>(),
                eq(10L),
            ),
        ).thenReturn(listOf(rule(id = 1L, scopeType = ModerationScopeType.GLOBAL)))

        val rules = repository.activeRulesForRoom(10L)

        assertEquals(listOf(1L), rules.map { it.id })
        verify(jdbcTemplate).query(
            org.mockito.ArgumentMatchers.contains("scope_type = 'GLOBAL'"),
            any<org.springframework.jdbc.core.RowMapper<ModerationRuleRecord>>(),
            eq(10L),
        )
    }

    private fun rule(
        id: Long,
        scopeType: ModerationScopeType,
    ): ModerationRuleRecord {
        return ModerationRuleRecord(
            id = id,
            scopeType = scopeType,
            roomId = null,
            pattern = "blocked",
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
            enabled = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
        )
    }
}
```

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.ModerationRuleJdbcRepositoryTest --no-daemon
```

Expected: FAIL because `ModerationRuleJdbcRepository` and `ModerationRuleRecord` do not exist.

- [ ] **Step 2: Write repository tests for active sanction lookup**

Create `chat-persistence/src/test/kotlin/repository/UserSanctionJdbcRepositoryTest.kt`:

```kotlin
package com.chat.persistence.repository

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class UserSanctionJdbcRepositoryTest {

    @Test
    fun `activeSanctionsForUser는 room scoped active sanction만 조회한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = UserSanctionJdbcRepository(jdbcTemplate)

        `when`(
            jdbcTemplate.query(
                any(String::class.java),
                any<org.springframework.jdbc.core.RowMapper<UserSanctionRecord>>(),
                eq(10L),
                eq(7L),
                any(Instant::class.java),
            ),
        ).thenReturn(listOf(sanction(id = 2L, type = UserSanctionType.MUTE)))

        val sanctions = repository.activeSanctionsForUser(roomId = 10L, userId = 7L, now = Instant.parse("2026-06-26T00:00:00Z"))

        assertEquals(listOf(UserSanctionType.MUTE), sanctions.map { it.type })
        verify(jdbcTemplate).query(
            org.mockito.ArgumentMatchers.contains("active = true"),
            any<org.springframework.jdbc.core.RowMapper<UserSanctionRecord>>(),
            eq(10L),
            eq(7L),
            any(Instant::class.java),
        )
    }

    private fun sanction(id: Long, type: UserSanctionType): UserSanctionRecord {
        return UserSanctionRecord(
            id = id,
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = type,
            reason = "spam",
            expiresAt = null,
            active = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            revokedBy = null,
            revokedAt = null,
        )
    }
}
```

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.UserSanctionJdbcRepositoryTest --no-daemon
```

Expected: FAIL because `UserSanctionJdbcRepository` and `UserSanctionRecord` do not exist.

- [ ] **Step 3: Add DDL**

Append this block after `admin_audit_logs` indexes in `infra/postgres/message-partitions.sql`:

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

CREATE INDEX IF NOT EXISTS ix_moderation_rules_active_scope
ON moderation_rules (enabled, scope_type, room_id);

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

CREATE INDEX IF NOT EXISTS ix_user_sanctions_active_lookup
ON user_sanctions (active, user_id, scope_type, room_id, type, expires_at);

CREATE INDEX IF NOT EXISTS ix_user_sanctions_room_user_created_at
ON user_sanctions (room_id, user_id, created_at DESC);
```

- [ ] **Step 4: Add cache properties and cache names**

Modify `chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt`:

```kotlin
@ConfigurationProperties(prefix = "chat.cache")
data class ChatCacheProperties(
    val defaultTtl: Duration = Duration.ofMinutes(30),
    val usersTtl: Duration = Duration.ofHours(1),
    val chatRoomsTtl: Duration = Duration.ofMinutes(15),
    val chatRoomMembersTtl: Duration = Duration.ofMinutes(10),
    val messagesTtl: Duration = Duration.ofMinutes(5),
    val roomAdmissionPoliciesTtl: Duration = Duration.ofSeconds(10),
    val roomShardConfigsTtl: Duration = Duration.ofSeconds(10),
    val moderationRulesTtl: Duration = Duration.ofSeconds(10),
    val userSanctionsTtl: Duration = Duration.ofSeconds(10),
)
```

Modify `chat-persistence/src/main/kotlin/config/CacheConfig.kt`:

```kotlin
return RedisCacheManager.builder(connectionFactory)
    .cacheDefaults(configuration)
    .withCacheConfiguration("users", configuration.entryTtl(cacheProperties.usersTtl))
    .withCacheConfiguration("chatRooms", configuration.entryTtl(cacheProperties.chatRoomsTtl))
    .withCacheConfiguration("chatRoomMembers", configuration.entryTtl(cacheProperties.chatRoomMembersTtl))
    .withCacheConfiguration("messages", configuration.entryTtl(cacheProperties.messagesTtl))
    .withCacheConfiguration("roomAdmissionPolicies", configuration.entryTtl(cacheProperties.roomAdmissionPoliciesTtl))
    .withCacheConfiguration("roomShardConfigs", configuration.entryTtl(cacheProperties.roomShardConfigsTtl))
    .withCacheConfiguration("moderationRules", configuration.entryTtl(cacheProperties.moderationRulesTtl))
    .withCacheConfiguration("userSanctions", configuration.entryTtl(cacheProperties.userSanctionsTtl))
    .build()
```

- [ ] **Step 5: Implement repositories**

Create `chat-persistence/src/main/kotlin/repository/ModerationRuleJdbcRepository.kt`:

```kotlin
package com.chat.persistence.repository

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

data class ModerationRuleRecord(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val pattern: String,
    val matchType: ModerationMatchType,
    val action: ModerationAction,
    val reason: String?,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun toDto(): AdminModerationRuleDto = AdminModerationRuleDto(
        id = id,
        scopeType = scopeType,
        roomId = roomId,
        pattern = pattern,
        matchType = matchType,
        action = action,
        reason = reason,
        enabled = enabled,
        createdBy = createdBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Repository
class ModerationRuleJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    @Cacheable(value = ["moderationRules"], key = "#roomId")
    fun activeRulesForRoom(roomId: Long): List<ModerationRuleRecord> {
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            FROM moderation_rules
            WHERE enabled = true
              AND (
                scope_type = 'GLOBAL'
                OR (scope_type = 'ROOM' AND room_id = ?)
              )
            ORDER BY scope_type ASC, id ASC
            """.trimIndent(),
            ROW_MAPPER,
            roomId,
        )
    }

    fun listRules(roomId: Long?, enabled: Boolean?): List<ModerationRuleRecord> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (roomId != null) {
            conditions += "(scope_type = 'GLOBAL' OR room_id = ?)"
            args += roomId
        }
        if (enabled != null) {
            conditions += "enabled = ?"
            args += enabled
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            FROM moderation_rules
            $where
            ORDER BY id DESC
            """.trimIndent(),
            ROW_MAPPER,
            *args.toTypedArray(),
        )
    }

    fun create(actor: String, request: AdminCreateModerationRuleRequest): ModerationRuleRecord {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO moderation_rules (scope_type, room_id, pattern, match_type, action, reason, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            """.trimIndent(),
            ROW_MAPPER,
            request.scopeType.name,
            request.roomId,
            request.pattern,
            request.matchType.name,
            request.action.name,
            request.reason,
            actor,
        ) ?: error("failed to create moderation rule")
    }

    fun update(ruleId: Long, request: AdminUpdateModerationRuleRequest): ModerationRuleRecord {
        return jdbcTemplate.queryForObject(
            """
            UPDATE moderation_rules
            SET pattern = COALESCE(?, pattern),
                reason = COALESCE(?, reason),
                enabled = COALESCE(?, enabled),
                updated_at = now()
            WHERE id = ?
            RETURNING id, scope_type, room_id, pattern, match_type, action, reason, enabled, created_by, created_at, updated_at
            """.trimIndent(),
            ROW_MAPPER,
            request.pattern,
            request.reason,
            request.enabled,
            ruleId,
        ) ?: error("moderation rule not found: $ruleId")
    }

    fun disable(ruleId: Long): ModerationRuleRecord {
        return update(ruleId, AdminUpdateModerationRuleRequest(enabled = false))
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            ModerationRuleRecord(
                id = rs.getLong("id"),
                scopeType = ModerationScopeType.valueOf(rs.getString("scope_type")),
                roomId = rs.getObject("room_id", java.lang.Long::class.java)?.toLong(),
                pattern = rs.getString("pattern"),
                matchType = ModerationMatchType.valueOf(rs.getString("match_type")),
                action = ModerationAction.valueOf(rs.getString("action")),
                reason = rs.getString("reason"),
                enabled = rs.getBoolean("enabled"),
                createdBy = rs.getString("created_by"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
```

Create `chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt`:

```kotlin
package com.chat.persistence.repository

import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

data class UserSanctionRecord(
    val id: Long,
    val scopeType: ModerationScopeType,
    val roomId: Long?,
    val userId: Long,
    val type: UserSanctionType,
    val reason: String?,
    val expiresAt: Instant?,
    val active: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val revokedBy: String?,
    val revokedAt: Instant?,
) {
    fun toDto(): AdminUserSanctionDto = AdminUserSanctionDto(
        id = id,
        scopeType = scopeType,
        roomId = roomId,
        userId = userId,
        type = type,
        reason = reason,
        expiresAt = expiresAt,
        active = active,
        createdBy = createdBy,
        createdAt = createdAt,
        revokedBy = revokedBy,
        revokedAt = revokedAt,
    )
}

@Repository
class UserSanctionJdbcRepository(
    @Qualifier("jdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    @Cacheable(value = ["userSanctions"], key = "T(java.lang.String).valueOf(#roomId).concat(':').concat(T(java.lang.String).valueOf(#userId))")
    fun activeSanctionsForUser(roomId: Long, userId: Long, now: Instant): List<UserSanctionRecord> {
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            FROM user_sanctions
            WHERE active = true
              AND user_id = ?
              AND scope_type = 'ROOM'
              AND room_id = ?
              AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY id DESC
            """.trimIndent(),
            ROW_MAPPER,
            userId,
            roomId,
            java.sql.Timestamp.from(now),
        )
    }

    fun listSanctions(roomId: Long?, userId: Long?, active: Boolean?): List<UserSanctionRecord> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (roomId != null) {
            conditions += "room_id = ?"
            args += roomId
        }
        if (userId != null) {
            conditions += "user_id = ?"
            args += userId
        }
        if (active != null) {
            conditions += "active = ?"
            args += active
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return jdbcTemplate.query(
            """
            SELECT id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            FROM user_sanctions
            $where
            ORDER BY id DESC
            """.trimIndent(),
            ROW_MAPPER,
            *args.toTypedArray(),
        )
    }

    fun create(actor: String, request: AdminCreateUserSanctionRequest): UserSanctionRecord {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO user_sanctions (scope_type, room_id, user_id, type, reason, expires_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            """.trimIndent(),
            ROW_MAPPER,
            request.scopeType.name,
            request.roomId,
            request.userId,
            request.type.name,
            request.reason,
            request.expiresAt?.let { java.sql.Timestamp.from(it) },
            actor,
        ) ?: error("failed to create user sanction")
    }

    fun revoke(actor: String, sanctionId: Long): UserSanctionRecord {
        return jdbcTemplate.queryForObject(
            """
            UPDATE user_sanctions
            SET active = false,
                revoked_by = ?,
                revoked_at = now()
            WHERE id = ?
            RETURNING id, scope_type, room_id, user_id, type, reason, expires_at, active, created_by, created_at, revoked_by, revoked_at
            """.trimIndent(),
            ROW_MAPPER,
            actor,
            sanctionId,
        ) ?: error("user sanction not found: $sanctionId")
    }

    private companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            UserSanctionRecord(
                id = rs.getLong("id"),
                scopeType = ModerationScopeType.valueOf(rs.getString("scope_type")),
                roomId = rs.getObject("room_id", java.lang.Long::class.java)?.toLong(),
                userId = rs.getLong("user_id"),
                type = UserSanctionType.valueOf(rs.getString("type")),
                reason = rs.getString("reason"),
                expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                active = rs.getBoolean("active"),
                createdBy = rs.getString("created_by"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                revokedBy = rs.getString("revoked_by"),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }
    }
}
```

- [ ] **Step 6: Extend cache serializer test**

Append to `chat-persistence/src/test/kotlin/config/CacheConfigTest.kt`:

```kotlin
@Test
fun `Redis cache serializer는 ModerationRuleRecord list 타입을 보존한다`() {
    val serializer = CacheConfig.redisCacheValueSerializer()
    val rules = listOf(
        ModerationRuleRecord(
            id = 1L,
            scopeType = ModerationScopeType.GLOBAL,
            roomId = null,
            pattern = "blocked",
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
            enabled = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
        ),
    )

    val restored = serializer.deserialize(serializer.serialize(rules))

    assertTrue(restored is List<*>)
    assertEquals(rules, restored)
}
```

Add imports for `ModerationRuleRecord`, DTO enums, and `Instant`.

- [ ] **Step 7: Run focused repository/config tests**

Run:

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.repository.ModerationRuleJdbcRepositoryTest \
  --tests com.chat.persistence.repository.UserSanctionJdbcRepositoryTest \
  --tests com.chat.persistence.config.CacheConfigTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add infra/postgres/message-partitions.sql \
  chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt \
  chat-persistence/src/main/kotlin/config/CacheConfig.kt \
  chat-persistence/src/main/kotlin/repository/ModerationRuleJdbcRepository.kt \
  chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt \
  chat-persistence/src/test/kotlin/repository/ModerationRuleJdbcRepositoryTest.kt \
  chat-persistence/src/test/kotlin/repository/UserSanctionJdbcRepositoryTest.kt \
  chat-persistence/src/test/kotlin/config/CacheConfigTest.kt
git commit -m "feat: add moderation repositories"
```

---

### Task 3: Runtime Moderation and Sanction Services

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/MessageModerationService.kt`
- Create: `chat-persistence/src/main/kotlin/service/UserSanctionService.kt`
- Test: `chat-persistence/src/test/kotlin/service/MessageModerationServiceTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/UserSanctionServiceTest.kt`

- [ ] **Step 1: Write moderation service tests**

Create `chat-persistence/src/test/kotlin/service/MessageModerationServiceTest.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import com.chat.persistence.repository.ModerationRuleRecord
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.function.Consumer

class MessageModerationServiceTest {

    @Test
    fun `global contains rule과 매칭되면 moderation rejected 예외와 metric을 남긴다`() {
        val repository = mock(ModerationRuleJdbcRepository::class.java)
        `when`(repository.activeRulesForRoom(10L)).thenReturn(
            listOf(rule(scopeType = ModerationScopeType.GLOBAL, roomId = null, pattern = "blocked")),
        )
        val meterRegistry = SimpleMeterRegistry()
        val service = MessageModerationService(repository, meterProvider(meterRegistry))

        val exception = assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowed(roomId = 10L, senderId = 7L, content = "this is BLOCKED text", messageType = MessageType.TEXT)
        }

        assertEquals("message blocked by moderation policy", exception.message)
        assertEquals(
            1.0,
            meterRegistry.counter(
                "chat.message.moderation.rejected",
                "reason", "blocked_word",
                "scope", "global",
                "action", "reject",
            ).count(),
        )
    }

    @Test
    fun `room contains rule과 매칭되지 않으면 통과한다`() {
        val repository = mock(ModerationRuleJdbcRepository::class.java)
        `when`(repository.activeRulesForRoom(10L)).thenReturn(
            listOf(rule(scopeType = ModerationScopeType.ROOM, roomId = 10L, pattern = "blocked")),
        )
        val service = MessageModerationService(repository, null)

        service.requireAllowed(roomId = 10L, senderId = 7L, content = "clean text", messageType = MessageType.TEXT)
    }

    private fun rule(scopeType: ModerationScopeType, roomId: Long?, pattern: String): ModerationRuleRecord {
        return ModerationRuleRecord(
            id = 1L,
            scopeType = scopeType,
            roomId = roomId,
            pattern = pattern,
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
            enabled = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
        )
    }

    private fun meterProvider(registry: SimpleMeterRegistry): ObjectProvider<io.micrometer.core.instrument.MeterRegistry> {
        return object : ObjectProvider<io.micrometer.core.instrument.MeterRegistry> {
            override fun getObject(vararg args: Any): io.micrometer.core.instrument.MeterRegistry = registry
            override fun getIfAvailable(): io.micrometer.core.instrument.MeterRegistry = registry
            override fun getIfUnique(): io.micrometer.core.instrument.MeterRegistry = registry
            override fun getObject(): io.micrometer.core.instrument.MeterRegistry = registry
            override fun iterator(): MutableIterator<io.micrometer.core.instrument.MeterRegistry> = mutableListOf(registry).iterator()
            override fun forEach(action: Consumer<in io.micrometer.core.instrument.MeterRegistry>) = action.accept(registry)
        }
    }
}
```

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.MessageModerationServiceTest --no-daemon
```

Expected: FAIL because `MessageModerationService` does not exist.

- [ ] **Step 2: Write sanction service tests**

Create `chat-persistence/src/test/kotlin/service/UserSanctionServiceTest.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UserSanctionServiceTest {

    @Test
    fun `mute 제재가 있으면 메시지 전송을 거부한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeSanctionsForUser(eq(10L), eq(7L), eq(now))).thenReturn(
            listOf(sanction(UserSanctionType.MUTE)),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        val exception = assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }

        assertEquals("user is restricted from sending messages", exception.message)
    }

    @Test
    fun `활성 제재가 없으면 통과한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeSanctionsForUser(eq(10L), eq(7L), eq(now))).thenReturn(emptyList())
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        service.requireAllowedToSend(roomId = 10L, userId = 7L)
    }

    private fun sanction(type: UserSanctionType): UserSanctionRecord {
        return UserSanctionRecord(
            id = 1L,
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = type,
            reason = "spam",
            expiresAt = null,
            active = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            revokedBy = null,
            revokedAt = null,
        )
    }
}
```

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.UserSanctionServiceTest --no-daemon
```

Expected: FAIL because `UserSanctionService` does not exist.

- [ ] **Step 3: Implement `MessageModerationService`**

Create `chat-persistence/src/main/kotlin/service/MessageModerationService.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.ModerationRuleRecord
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

interface MessageModerationPolicyService {
    fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType)

    object Noop : MessageModerationPolicyService {
        override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) = Unit
    }
}

@Service
class MessageModerationService(
    private val moderationRuleRepository: ModerationRuleJdbcRepository,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : MessageModerationPolicyService {
    override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) {
        if (messageType != MessageType.TEXT || content.isNullOrBlank()) {
            return
        }
        val rules = moderationRuleRepository.activeRulesForRoom(roomId)
        val matched = rules.firstOrNull { rule -> rule.matches(content) } ?: return
        recordRejected(matched)
        throw MessageModerationRejectedException("message blocked by moderation policy")
    }

    private fun ModerationRuleRecord.matches(content: String): Boolean {
        if (action != ModerationAction.REJECT || matchType != ModerationMatchType.CONTAINS) {
            return false
        }
        return content.contains(pattern, ignoreCase = true)
    }

    private fun recordRejected(rule: ModerationRuleRecord) {
        val scope = if (rule.scopeType == ModerationScopeType.GLOBAL) "global" else "room"
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.message.moderation.rejected")
                .tag("reason", "blocked_word")
                .tag("scope", scope)
                .tag("action", "reject")
                .register(registry)
                .increment()
        }
    }
}
```

- [ ] **Step 4: Implement `UserSanctionService`**

Create `chat-persistence/src/main/kotlin/service/UserSanctionService.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Clock

interface UserSanctionPolicyService {
    fun requireAllowedToSend(roomId: Long, userId: Long)

    object Noop : UserSanctionPolicyService {
        override fun requireAllowedToSend(roomId: Long, userId: Long) = Unit
    }
}

@Service
class UserSanctionService(
    private val userSanctionRepository: UserSanctionJdbcRepository,
    private val clock: Clock,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : UserSanctionPolicyService {
    override fun requireAllowedToSend(roomId: Long, userId: Long) {
        val sanction = userSanctionRepository.activeSanctionsForUser(roomId, userId, clock.instant())
            .firstOrNull { it.type == UserSanctionType.MUTE || it.type == UserSanctionType.BAN }
            ?: return
        recordRejected(sanction)
        throw MessageModerationRejectedException("user is restricted from sending messages")
    }

    private fun recordRejected(sanction: UserSanctionRecord) {
        val reason = when (sanction.type) {
            UserSanctionType.MUTE -> "muted"
            UserSanctionType.BAN -> "banned"
            UserSanctionType.SUSPEND_RESERVED -> "banned"
        }
        val scope = if (sanction.scopeType == ModerationScopeType.GLOBAL) "global" else "room"
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("chat.message.moderation.rejected")
                .tag("reason", reason)
                .tag("scope", scope)
                .tag("action", "reject")
                .register(registry)
                .increment()
        }
    }
}
```

- [ ] **Step 5: Run focused service tests**

Run:

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.service.MessageModerationServiceTest \
  --tests com.chat.persistence.service.UserSanctionServiceTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/MessageModerationService.kt \
  chat-persistence/src/main/kotlin/service/UserSanctionService.kt \
  chat-persistence/src/test/kotlin/service/MessageModerationServiceTest.kt \
  chat-persistence/src/test/kotlin/service/UserSanctionServiceTest.kt
git commit -m "feat: add moderation runtime gates"
```

---

### Task 4: Wire Moderation Gates Into Message Sending and Error Contracts

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`
- Modify: `chat-api/src/main/kotlin/controller/GlobalExceptionHandler.kt`
- Modify: `chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt`
- Test: `chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt`
- Test: `chat-api/src/test/kotlin/controller/GlobalExceptionHandlerTest.kt`
- Test: `chat-websocket/src/test/kotlin/handler/ChatWebSocketHandlerTest.kt`

- [ ] **Step 1: Add failing ChatServiceImpl moderation tests**

Append to `ChatServiceImplMessageContractTest`:

```kotlin
@Test
fun `moderation 거부 시 sequence 발급과 stream append를 수행하지 않는다`() {
    val messageStreamProducer = mock(MessageStreamProducer::class.java)
    val fixture = chatServiceFixture(
        messageStreamProducer = messageStreamProducer,
        messageModerationPolicyService = RejectingMessageModerationPolicyService("message blocked by moderation policy"),
    )
    val clientMessageId = "client-message-1"
    `when`(
        fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(10L, 7L, clientMessageId)
    ).thenReturn(Optional.empty())

    val exception = assertThrows(MessageModerationRejectedException::class.java) {
        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "blocked",
                clientMessageId = clientMessageId,
            ),
            senderId = 7L,
        )
    }

    assertEquals("message blocked by moderation policy", exception.message)
    verify(messageStreamProducer, never()).append(anyMessageStreamEnvelope())
    verify(fixture.redisTemplate.opsForValue(), never()).increment("chat:sequence:10", 1L)
}

@Test
fun `같은 clientMessageId 재전송은 moderation을 다시 검사하지 않는다`() {
    val moderation = RecordingMessageModerationPolicyService()
    val fixture = chatServiceFixture(messageModerationPolicyService = moderation)
    val clientMessageId = "client-message-1"
    `when`(
        fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(10L, 7L, clientMessageId)
    ).thenReturn(
        Optional.of(
            Message(
                id = 101L,
                messageId = "msg-existing",
                clientMessageId = clientMessageId,
                chatRoom = fixture.chatRoom,
                sender = fixture.sender,
                type = MessageType.TEXT,
                content = "hello",
                sequenceNumber = 5L,
                roomSeq = 5L,
                createdAt = LocalDateTime.parse("2026-06-12T12:00:00"),
            ),
        ),
    )

    fixture.chatService.sendMessage(
        SendMessageRequest(chatRoomId = 10L, type = MessageType.TEXT, content = "hello", clientMessageId = clientMessageId),
        senderId = 7L,
    )

    assertEquals(0, moderation.callCount)
}
```

Update the fixture signature to accept:

```kotlin
messageModerationPolicyService: MessageModerationPolicyService = MessageModerationPolicyService.Noop,
userSanctionPolicyService: UserSanctionPolicyService = UserSanctionPolicyService.Noop,
```

Add helper classes:

```kotlin
private class RejectingMessageModerationPolicyService(
    private val message: String,
) : MessageModerationPolicyService {
    override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) {
        throw MessageModerationRejectedException(message)
    }
}

private class RecordingMessageModerationPolicyService : MessageModerationPolicyService {
    var callCount = 0

    override fun requireAllowed(roomId: Long, senderId: Long, content: String?, messageType: MessageType) {
        callCount += 1
    }
}
```

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.ChatServiceImplMessageContractTest --no-daemon
```

Expected: FAIL because `ChatServiceImpl` constructor does not accept the new services.

- [ ] **Step 2: Wire services in ChatServiceImpl**

Modify the `ChatServiceImpl` constructor:

```kotlin
class ChatServiceImpl(
    private val chatRoomRepository: ChatRoomRepository,
    private val userRepository: UserRepository,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val messageRepository: MessageRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageSequenceService: MessageSequenceService,
    private val messageStreamProducer: MessageStreamProducer,
    private val messageAdmissionPolicyService: MessageAdmissionPolicyService,
    private val roomTrafficStatsService: RoomTrafficStatsService,
    private val roomStorageConfigReader: RoomStorageConfigReader,
    private val userSanctionPolicyService: UserSanctionPolicyService = UserSanctionPolicyService.Noop,
    private val messageModerationPolicyService: MessageModerationPolicyService = MessageModerationPolicyService.Noop,
) : ChatService {
```

Insert after idempotency check and before admission:

```kotlin
userSanctionPolicyService.requireAllowedToSend(
    roomId = request.chatRoomId,
    userId = senderId,
)
messageModerationPolicyService.requireAllowed(
    roomId = request.chatRoomId,
    senderId = senderId,
    content = request.content,
    messageType = request.type ?: MessageType.TEXT,
)
```

- [ ] **Step 3: Add REST error handler test and implementation**

Append to `GlobalExceptionHandlerTest` the same pattern as the existing admission test:

```kotlin
@GetMapping("/moderation-rejected")
fun moderationRejected(): String {
    throw MessageModerationRejectedException("message blocked by moderation policy")
}
```

Add a test:

```kotlin
@Test
fun `moderation rejected는 403을 반환한다`() {
    mockMvc.get("/test/moderation-rejected")
        .andExpect {
            status { isForbidden() }
            jsonPath("$.message") { value("message blocked by moderation policy") }
        }
}
```

Modify `GlobalExceptionHandler.kt`:

```kotlin
@ExceptionHandler(MessageModerationRejectedException::class)
fun handleMessageModerationRejectedException(
    exception: MessageModerationRejectedException,
    request: HttpServletRequest,
): ResponseEntity<ApiErrorResponse> {
    return buildResponse(
        status = HttpStatus.FORBIDDEN,
        message = exception.message ?: "메시지가 moderation 정책에 의해 거부되었습니다.",
        path = request.requestURI,
    )
}
```

Run:

```bash
./gradlew :chat-api:test --tests controller.GlobalExceptionHandlerTest --no-daemon
```

Expected: PASS after import and handler are added.

- [ ] **Step 4: Add WebSocket error test and implementation**

Append to `ChatWebSocketHandlerTest`:

```kotlin
@Test
fun `moderation 거부는 MESSAGE_ACCEPTED 없이 moderation 에러를 outbound 경로로 전송한다`() {
    val sessionManager = mock(WebSocketSessionManager::class.java)
    val chatService = mock(ChatService::class.java)
    val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    val handler = ChatWebSocketHandler(
        sessionManager = sessionManager,
        chatService = chatService,
        objectMapper = objectMapper,
        webSocketProperties = WebSocketProperties(userIdAttribute = "userId"),
    )
    val session = mock(WebSocketSession::class.java)
    `when`(session.id).thenReturn("session-1")
    `when`(session.attributes).thenReturn(mutableMapOf<String, Any>("userId" to 7L))
    `when`(
        chatService.sendMessage(
            SendMessageRequest(chatRoomId = 10L, type = MessageType.TEXT, content = "blocked", clientMessageId = "client-1"),
            7L,
        )
    ).thenThrow(MessageModerationRejectedException("message blocked by moderation policy"))

    handler.handleMessage(
        session,
        TextMessage(
            """
            {
              "type": "SEND_MESSAGE",
              "chatRoomId": 10,
              "messageType": "TEXT",
              "content": "blocked",
              "clientMessageId": "client-1"
            }
            """.trimIndent(),
        ),
    )

    val outboundInvocation = mockingDetails(sessionManager).invocations
        .single { it.method.name == "sendTextToSession" }
    val payload = outboundInvocation.arguments[1] as String
    assertTrue(payload.contains("\"type\":\"ERROR\""), payload)
    assertTrue(payload.contains("\"code\":\"MESSAGE_MODERATION_REJECTED\""), payload)
    assertTrue(payload.contains("\"message\":\"message blocked by moderation policy\""), payload)
    assertTrue(!payload.contains("\"type\":\"MESSAGE_ACCEPTED\""), payload)
}
```

Modify `ChatWebSocketHandler.handleSendMessage()`:

```kotlin
} catch (e: MessageModerationRejectedException) {
    sendErrorMessage(
        session = session,
        errorMessage = e.message ?: "메시지가 moderation 정책에 의해 거부되었습니다.",
        errorCode = WebSocketErrorCode.MESSAGE_MODERATION_REJECTED.name,
    )
    return
} catch (e: MessageAdmissionRejectedException) {
```

Add enum value wherever `WebSocketErrorCode` is defined:

```kotlin
MESSAGE_MODERATION_REJECTED,
```

Run:

```bash
./gradlew :chat-websocket:test --tests handler.ChatWebSocketHandlerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Run combined focused tests**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.ChatServiceImplMessageContractTest --no-daemon
./gradlew :chat-api:test --tests controller.GlobalExceptionHandlerTest --no-daemon
./gradlew :chat-websocket:test --tests handler.ChatWebSocketHandlerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt \
  chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt \
  chat-api/src/main/kotlin/controller/GlobalExceptionHandler.kt \
  chat-api/src/test/kotlin/controller/GlobalExceptionHandlerTest.kt \
  chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt \
  chat-websocket/src/test/kotlin/handler/ChatWebSocketHandlerTest.kt
git commit -m "feat: enforce moderation before message acceptance"
```

---

### Task 5: Admin Moderation Service and Controller

**Files:**
- Create: `chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt`
- Create: `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminModerationController.kt`
- Test: `chat-persistence/src/test/kotlin/service/AdminModerationServiceImplTest.kt`
- Test: `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminModerationControllerTest.kt`

- [ ] **Step 1: Write AdminModerationServiceImpl tests**

Create `chat-persistence/src/test/kotlin/service/AdminModerationServiceImplTest.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.ModerationRuleRecord
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

class AdminModerationServiceImplTest {

    @Test
    fun `createRule은 repository 저장과 audit log를 transaction으로 묶는다`() {
        val method = AdminModerationServiceImpl::class.java.getMethod(
            "createRule",
            String::class.java,
            AdminCreateModerationRuleRequest::class.java,
        )

        assertNotNull(method.getAnnotation(Transactional::class.java))
    }

    @Test
    fun `createRule은 rule을 저장하고 audit log를 남긴다`() {
        val fixture = fixture()
        val request = AdminCreateModerationRuleRequest(
            scopeType = ModerationScopeType.GLOBAL,
            pattern = "blocked",
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
        )
        `when`(fixture.ruleRepository.create("admin-local", request)).thenReturn(ruleRecord())

        val response = fixture.service.createRule("admin-local", request)

        org.junit.jupiter.api.Assertions.assertEquals(1L, response.id)
        verify(fixture.auditRepository).record(
            eq("admin-local"),
            eq("ADMIN_MODERATION_RULE_CREATED"),
            eq("MODERATION_RULE"),
            eq("rule:1"),
            contains("blocked"),
        )
    }

    @Test
    fun `createSanction은 SUSPEND_RESERVED 생성을 거부한다`() {
        val fixture = fixture()
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.GLOBAL,
            userId = 7L,
            type = UserSanctionType.SUSPEND_RESERVED,
        )

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            fixture.service.createSanction("admin-local", request)
        }
    }

    private fun fixture(): Fixture {
        val ruleRepository = mock(ModerationRuleJdbcRepository::class.java)
        val sanctionRepository = mock(UserSanctionJdbcRepository::class.java)
        val auditRepository = mock(AdminAuditLogRepository::class.java)
        return Fixture(
            service = AdminModerationServiceImpl(
                ruleRepository = ruleRepository,
                sanctionRepository = sanctionRepository,
                auditLogRepository = auditRepository,
                objectMapper = jacksonObjectMapper(),
            ),
            ruleRepository = ruleRepository,
            sanctionRepository = sanctionRepository,
            auditRepository = auditRepository,
        )
    }

    private data class Fixture(
        val service: AdminModerationServiceImpl,
        val ruleRepository: ModerationRuleJdbcRepository,
        val sanctionRepository: UserSanctionJdbcRepository,
        val auditRepository: AdminAuditLogRepository,
    )

    private fun ruleRecord(): ModerationRuleRecord = ModerationRuleRecord(
        id = 1L,
        scopeType = ModerationScopeType.GLOBAL,
        roomId = null,
        pattern = "blocked",
        matchType = ModerationMatchType.CONTAINS,
        action = ModerationAction.REJECT,
        reason = "blocked phrase",
        enabled = true,
        createdBy = "admin-local",
        createdAt = Instant.parse("2026-06-26T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
    )
}
```

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminModerationServiceImplTest --no-daemon
```

Expected: FAIL because `AdminModerationServiceImpl` does not exist.

- [ ] **Step 2: Implement AdminModerationServiceImpl**

Create `chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt`:

```kotlin
package com.chat.persistence.service

import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.service.AdminModerationService
import com.chat.persistence.repository.AdminAuditLogRepository
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminModerationServiceImpl(
    private val ruleRepository: ModerationRuleJdbcRepository,
    private val sanctionRepository: UserSanctionJdbcRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val objectMapper: ObjectMapper,
) : AdminModerationService {
    override fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto> {
        return ruleRepository.listRules(roomId, enabled).map { it.toDto() }
    }

    @Transactional
    @Caching(evict = [
        CacheEvict(value = ["moderationRules"], allEntries = true),
    ])
    override fun createRule(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto {
        validateRuleRequest(request.scopeType, request.roomId, request.pattern)
        val record = ruleRepository.create(actor, request)
        audit(actor, "ADMIN_MODERATION_RULE_CREATED", "MODERATION_RULE", "rule:${record.id}", request)
        return record.toDto()
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun updateRule(actor: String, ruleId: Long, request: AdminUpdateModerationRuleRequest): AdminModerationRuleDto {
        if (request.pattern != null && request.pattern.isBlank()) {
            throw IllegalArgumentException("pattern must not be blank")
        }
        val record = ruleRepository.update(ruleId, request)
        audit(actor, "ADMIN_MODERATION_RULE_UPDATED", "MODERATION_RULE", "rule:${record.id}", request)
        return record.toDto()
    }

    @Transactional
    @CacheEvict(value = ["moderationRules"], allEntries = true)
    override fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto {
        val record = ruleRepository.disable(ruleId)
        audit(actor, "ADMIN_MODERATION_RULE_DISABLED", "MODERATION_RULE", "rule:${record.id}", mapOf("ruleId" to ruleId))
        return record.toDto()
    }

    override fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<AdminUserSanctionDto> {
        return sanctionRepository.listSanctions(roomId, userId, active).map { it.toDto() }
    }

    @Transactional
    @CacheEvict(value = ["userSanctions"], key = "T(java.lang.String).valueOf(#request.roomId).concat(':').concat(T(java.lang.String).valueOf(#request.userId))")
    override fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto {
        validateSanctionRequest(request)
        val record = sanctionRepository.create(actor, request)
        audit(actor, "ADMIN_USER_SANCTION_CREATED", "USER_SANCTION", "sanction:${record.id}", request)
        return record.toDto()
    }

    @Transactional
    @CacheEvict(value = ["userSanctions"], allEntries = true)
    override fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto {
        val record = sanctionRepository.revoke(actor, sanctionId)
        audit(actor, "ADMIN_USER_SANCTION_REVOKED", "USER_SANCTION", "sanction:${record.id}", mapOf("sanctionId" to sanctionId))
        return record.toDto()
    }

    private fun validateRuleRequest(scopeType: ModerationScopeType, roomId: Long?, pattern: String) {
        if (scopeType == ModerationScopeType.GLOBAL && roomId != null) {
            throw IllegalArgumentException("GLOBAL rule must not have roomId")
        }
        if (scopeType == ModerationScopeType.ROOM && roomId == null) {
            throw IllegalArgumentException("ROOM rule requires roomId")
        }
        if (pattern.isBlank()) {
            throw IllegalArgumentException("pattern must not be blank")
        }
    }

    private fun validateSanctionRequest(request: AdminCreateUserSanctionRequest) {
        if (request.type == UserSanctionType.SUSPEND_RESERVED) {
            throw IllegalArgumentException("SUSPEND_RESERVED is reserved for Phase 8.6")
        }
        if (request.scopeType != ModerationScopeType.ROOM || request.roomId == null) {
            throw IllegalArgumentException("Phase 8.5 supports ROOM scoped sanctions only")
        }
    }

    private fun audit(actor: String, action: String, targetType: String, targetId: String, metadata: Any) {
        auditLogRepository.record(
            actor = actor,
            action = action,
            targetType = targetType,
            targetId = targetId,
            metadataJson = objectMapper.writeValueAsString(metadata),
        )
    }
}
```

- [ ] **Step 3: Write AdminModerationController tests**

Create `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminModerationControllerTest.kt`:

```kotlin
package com.chat.admin.controller

import com.chat.admin.config.AdminProperties
import com.chat.admin.security.AdminTokenVerifier
import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.service.AdminModerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class AdminModerationControllerTest {
    private lateinit var mockMvc: MockMvc
    private lateinit var service: RecordingAdminModerationService

    @BeforeEach
    fun setUp() {
        service = RecordingAdminModerationService()
        val properties = AdminProperties(token = "local-admin-token", actor = "admin-local")
        mockMvc = MockMvcBuilders
            .standaloneSetup(AdminModerationController(AdminTokenVerifier(properties), service))
            .build()
    }

    @Test
    fun `rule 생성은 token을 검증하고 service로 전달한다`() {
        mockMvc.post("/admin/moderation/rules") {
            header("X-Admin-Token", "local-admin-token")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "scopeType": "ROOM",
                  "roomId": 10,
                  "pattern": "blocked",
                  "matchType": "CONTAINS",
                  "action": "REJECT",
                  "reason": "blocked phrase"
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
        }

        assertEquals("admin-local", service.createRuleActor)
        assertEquals(10L, service.createRuleRequest?.roomId)
        assertEquals("blocked", service.createRuleRequest?.pattern)
    }

    @Test
    fun `token이 없으면 moderation API를 401로 거부한다`() {
        mockMvc.get("/admin/moderation/rules")
            .andExpect { status { isUnauthorized() } }
    }

    private class RecordingAdminModerationService : AdminModerationService {
        var createRuleActor: String? = null
        var createRuleRequest: AdminCreateModerationRuleRequest? = null

        override fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto> = emptyList()

        override fun createRule(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto {
            createRuleActor = actor
            createRuleRequest = request
            return AdminModerationRuleDto(
                id = 1L,
                scopeType = request.scopeType,
                roomId = request.roomId,
                pattern = request.pattern,
                matchType = request.matchType,
                action = request.action,
                reason = request.reason,
                enabled = true,
                createdBy = actor,
                createdAt = Instant.parse("2026-06-26T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
            )
        }

        override fun updateRule(actor: String, ruleId: Long, request: com.chat.domain.dto.AdminUpdateModerationRuleRequest): AdminModerationRuleDto {
            throw UnsupportedOperationException()
        }

        override fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto {
            throw UnsupportedOperationException()
        }

        override fun listSanctions(actor: String, roomId: Long?, userId: Long?, active: Boolean?): List<com.chat.domain.dto.AdminUserSanctionDto> = emptyList()

        override fun createSanction(actor: String, request: com.chat.domain.dto.AdminCreateUserSanctionRequest): com.chat.domain.dto.AdminUserSanctionDto {
            throw UnsupportedOperationException()
        }

        override fun revokeSanction(actor: String, sanctionId: Long): com.chat.domain.dto.AdminUserSanctionDto {
            throw UnsupportedOperationException()
        }
    }
}
```

Run:

```bash
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminModerationControllerTest --no-daemon
```

Expected: FAIL because `AdminModerationController` does not exist.

- [ ] **Step 4: Implement AdminModerationController**

Create `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminModerationController.kt`:

```kotlin
package com.chat.admin.controller

import com.chat.admin.security.AdminTokenVerifier
import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.service.AdminModerationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/moderation")
class AdminModerationController(
    private val adminTokenVerifier: AdminTokenVerifier,
    private val adminModerationService: AdminModerationService,
) {
    @GetMapping("/rules")
    fun listRules(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestParam(required = false) roomId: Long?,
        @RequestParam(required = false) enabled: Boolean?,
    ): List<AdminModerationRuleDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.listRules(actor, roomId, enabled)
    }

    @PostMapping("/rules")
    fun createRule(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestBody request: AdminCreateModerationRuleRequest,
    ): ResponseEntity<AdminModerationRuleDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return ResponseEntity.status(HttpStatus.CREATED).body(adminModerationService.createRule(actor, request))
    }

    @PatchMapping("/rules/{ruleId}")
    fun updateRule(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable ruleId: Long,
        @RequestBody request: AdminUpdateModerationRuleRequest,
    ): AdminModerationRuleDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.updateRule(actor, ruleId, request)
    }

    @PostMapping("/rules/{ruleId}/disable")
    fun disableRule(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable ruleId: Long,
    ): AdminModerationRuleDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.disableRule(actor, ruleId)
    }

    @GetMapping("/sanctions")
    fun listSanctions(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestParam(required = false) roomId: Long?,
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) active: Boolean?,
    ): List<AdminUserSanctionDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.listSanctions(actor, roomId, userId, active)
    }

    @PostMapping("/sanctions")
    fun createSanction(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestBody request: AdminCreateUserSanctionRequest,
    ): ResponseEntity<AdminUserSanctionDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return ResponseEntity.status(HttpStatus.CREATED).body(adminModerationService.createSanction(actor, request))
    }

    @PostMapping("/sanctions/{sanctionId}/revoke")
    fun revokeSanction(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable sanctionId: Long,
    ): AdminUserSanctionDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.revokeSanction(actor, sanctionId)
    }

    private companion object {
        const val ADMIN_TOKEN_HEADER = "X-Admin-Token"
    }
}
```

- [ ] **Step 5: Run admin service/controller tests**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminModerationServiceImplTest --no-daemon
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminModerationControllerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/AdminModerationServiceImpl.kt \
  chat-persistence/src/test/kotlin/service/AdminModerationServiceImplTest.kt \
  chat-admin/src/main/kotlin/com/chat/admin/controller/AdminModerationController.kt \
  chat-admin/src/test/kotlin/com/chat/admin/controller/AdminModerationControllerTest.kt
git commit -m "feat: add admin moderation api"
```

---

### Task 6: Runtime Configuration, Verification Script, and Docs

**Files:**
- Modify: `.env.example`
- Modify: `mise.toml`
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Create: `scripts/verify-moderation.mjs`
- Modify: `README.md`
- Modify: `docs/configuration.md`
- Modify: `docs/observability_metrics.md`

- [ ] **Step 1: Add env/config values**

Add to `.env.example`:

```bash
CHAT_CACHE_MODERATION_RULES_TTL=10s
CHAT_CACHE_USER_SANCTIONS_TTL=10s
```

Add under `chat.cache` in `application-docker.yml`:

```yaml
    moderation-rules-ttl: ${CHAT_CACHE_MODERATION_RULES_TTL:10s}
    user-sanctions-ttl: ${CHAT_CACHE_USER_SANCTIONS_TTL:10s}
```

- [ ] **Step 2: Add verification script**

Create `scripts/verify-moderation.mjs`:

```javascript
#!/usr/bin/env node

const apiBaseUrl = process.env.CHAT_API_BASE_URL ?? 'http://localhost/api';
const adminBaseUrl = process.env.CHAT_ADMIN_BASE_URL ?? 'http://localhost/admin';
const adminToken = process.env.CHAT_ADMIN_TOKEN ?? 'local-admin-token';

async function main() {
  const blockedPattern = `phase85-blocked-${Date.now()}`;
  const unauthorized = await fetch(`${adminBaseUrl}/moderation/rules`);
  if (unauthorized.status !== 401) {
    throw new Error(`expected unauthenticated moderation rules request to return 401, got ${unauthorized.status}`);
  }

  const rule = await requestJson(`${adminBaseUrl}/moderation/rules`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'X-Admin-Token': adminToken,
    },
    body: JSON.stringify({
      scopeType: 'GLOBAL',
      pattern: blockedPattern,
      matchType: 'CONTAINS',
      action: 'REJECT',
      reason: 'phase8.5 smoke',
    }),
  });

  console.log(JSON.stringify({
    ok: true,
    createdRuleId: rule.id,
    checked: ['admin-auth-required', 'global-rule-created'],
    apiBaseUrl,
    adminBaseUrl,
  }, null, 2));
}

async function requestJson(url, init) {
  const response = await fetch(url, init);
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`${init.method ?? 'GET'} ${url} failed with ${response.status}: ${body}`);
  }
  return body ? JSON.parse(body) : null;
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
```

This script deliberately verifies admin auth and rule creation only. REST/WebSocket send rejection is covered by automated tests in Task 4, because the running stack does not currently provide a stable authenticated synthetic user fixture for a smoke script.

- [ ] **Step 3: Add mise task**

Add to `mise.toml`:

```toml
"verify:moderation" = "node scripts/verify-moderation.mjs"
```

- [ ] **Step 4: Update docs**

Add to `docs/configuration.md`:

```markdown
| `CHAT_CACHE_MODERATION_RULES_TTL` | `10s` | 메시지 수락 전 moderation rule cache TTL |
| `CHAT_CACHE_USER_SANCTIONS_TTL` | `10s` | 메시지 수락 전 user sanction cache TTL |
```

Add to `docs/observability_metrics.md`:

```markdown
| `chat.message.moderation.rejected` | Counter | `reason`, `scope`, `action` | 금칙어, mute, ban으로 메시지 수락 전 거부된 횟수 |
```

Add to `README.md`:

````markdown
Phase 8.5 moderation smoke:

```bash
mise run verify:moderation
```
````

- [ ] **Step 5: Run script syntax and docs search**

Run:

```bash
node --check scripts/verify-moderation.mjs
rg -n "CHAT_CACHE_MODERATION_RULES_TTL|CHAT_CACHE_USER_SANCTIONS_TTL|verify:moderation|chat.message.moderation.rejected" .env.example mise.toml README.md docs chat-runtime-config/src/main/resources/application-docker.yml
```

Expected: `node --check` exits `0`; `rg` shows all new config/doc references.

- [ ] **Step 6: Commit**

```bash
git add .env.example mise.toml chat-runtime-config/src/main/resources/application-docker.yml \
  scripts/verify-moderation.mjs README.md docs/configuration.md docs/observability_metrics.md
git commit -m "docs: document moderation operations"
```

---

### Task 7: Final Verification

**Files:**
- No new files.

- [ ] **Step 1: Run focused test suite**

Run:

```bash
./gradlew :chat-domain:test --no-daemon
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.repository.ModerationRuleJdbcRepositoryTest \
  --tests com.chat.persistence.repository.UserSanctionJdbcRepositoryTest \
  --tests com.chat.persistence.service.MessageModerationServiceTest \
  --tests com.chat.persistence.service.UserSanctionServiceTest \
  --tests com.chat.persistence.service.AdminModerationServiceImplTest \
  --tests com.chat.persistence.service.ChatServiceImplMessageContractTest \
  --tests com.chat.persistence.config.CacheConfigTest \
  --no-daemon
./gradlew :chat-api:test --tests controller.GlobalExceptionHandlerTest --no-daemon
./gradlew :chat-websocket:test --tests handler.ChatWebSocketHandlerTest --no-daemon
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminModerationControllerTest --no-daemon
```

Expected: all commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run static checks**

Run:

```bash
node --check scripts/verify-moderation.mjs
git diff --check main...HEAD
git status --short --branch
```

Expected:

- `node --check` exits `0`.
- `git diff --check` prints no output.
- `git status --short --branch` shows the feature branch and no unstaged files.

- [ ] **Step 4: Optional running-stack smoke**

Run only if the Compose stack is already up and admin token is configured:

```bash
mise run verify:moderation
```

Expected: JSON output contains `"ok": true`.

If the stack is not running, do not claim this smoke passed. Report it as not run.

---

## Spec Coverage Self-Review

- DB-managed `GLOBAL + ROOM` moderation rules are covered by Task 1, Task 2, Task 3, Task 5.
- `MUTE`/`BAN` message-send-only sanctions are covered by Task 1, Task 2, Task 3, Task 5.
- Message acceptance gate before sequence and stream append is covered by Task 4.
- REST `403` and WebSocket `MESSAGE_MODERATION_REJECTED` are covered by Task 4.
- Admin audit log integration is covered by Task 5.
- Cache TTLs and cache names are covered by Task 2 and Task 6.
- Low-cardinality metric is covered by Task 3 and Task 6.
- `SUSPEND_RESERVED` is reserved and rejected in Task 5.
- `REGEX` execution is excluded by schema enum and DTO enum in Task 1 and Task 2.

## Placeholder Scan

The plan intentionally avoids incomplete markers and does not require unspecified follow-up code to satisfy Phase 8.5 1차 scope. Running-stack smoke beyond admin auth/rule creation is explicitly optional because it depends on authenticated synthetic user fixtures in the live environment.

## Type Consistency Check

- `ModerationScopeType`, `ModerationMatchType`, `ModerationAction`, and `UserSanctionType` are defined in Task 1 and reused in Tasks 2-6.
- `MessageModerationPolicyService` and `UserSanctionPolicyService` are defined in Task 3 and injected into `ChatServiceImpl` in Task 4.
- `AdminModerationService` is defined in Task 1 and implemented/used in Task 5.
- Cache names are consistently `moderationRules` and `userSanctions`.
