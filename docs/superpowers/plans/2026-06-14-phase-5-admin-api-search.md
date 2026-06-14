# Phase 5 Admin API And Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 방별/시간대별 history 조회, 키워드 검색, audit log, export job 생성/worker 처리, 1천만건 seed 검증 경로, `chat-admin` API에 대응하는 별도 `client-admin` 프론트 모듈을 제공한다.

**Architecture:** `chat-admin`은 관리자 controller/service/RBAC를 담당하고, `chat-domain`은 admin DTO와 service port를 제공한다. `chat-persistence`는 PostgreSQL `chat_messages` canonical store 기준의 history/search/export/audit SQL adapter를 제공하며, 조회는 read replica용 `messageReadJdbcTemplate`를 기본 사용한다. `client-admin`은 `chat-admin` API만 호출하는 별도 운영자 UI 모듈로 분리한다.

**Tech Stack:** Kotlin, Spring Boot MVC, Spring JDBC, PostgreSQL partitioned table, `pg_trgm`, `tsvector`, JUnit 5, MockMvc, Node.js seed script.

---

## 범위

- 관리자 인증은 Phase 5에서 간단한 shared token 방식으로 시작한다.
- 요청 헤더는 `X-Admin-Token`을 사용한다.
- 기본 token 설정값은 local/dev 검증을 위해 `local-admin-token`으로 둔다.
- 운영에서는 `CHAT_ADMIN_TOKEN`으로 반드시 override한다.
- 관리자 UI는 `client-admin` 모듈에서 제공한다.
- `client-admin`은 `X-Admin-Token` 입력, 방별/시간대별 history, 키워드 검색, room status, export job 생성 화면을 가진다.
- `client-admin`은 일반 사용자용 `client`와 분리해 배포할 수 있게 한다.
- 삭제/숨김/복구 실제 moderation mutation은 Phase 5에서 audit infrastructure와 repository method까지 준비하고, 운영 정책 확정 전에는 API로 열지 않는다.
- export는 실시간 채팅 writer/fanout worker와 분리될 수 있도록 `admin_message_export_jobs` 테이블에 job을 생성하고, `admin-export` role worker가 pending job을 처리한다.

## 파일 구조

- Create: `chat-domain/src/main/kotlin/dto/AdminDto.kt`
  - 관리자 history/search/export/audit/status 응답 DTO를 정의한다.
- Create: `chat-domain/src/main/kotlin/service/AdminChatService.kt`
  - `chat-admin` controller가 의존할 관리자 service port를 정의한다.
- Create: `chat-admin/src/main/kotlin/com/chat/admin/config/AdminProperties.kt`
  - `chat.admin.token`, pagination 기본값, max limit을 바인딩한다.
- Create: `chat-admin/src/main/kotlin/com/chat/admin/security/AdminTokenVerifier.kt`
  - `X-Admin-Token` 검증을 담당한다.
- Create: `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminChatController.kt`
  - `/admin/chat-rooms/{roomId}/messages`, `/admin/messages/search`, `/admin/exports/messages`, `/admin/rooms/{roomId}/status`를 제공한다.
- Create: `client-admin/package.json`
  - 관리자 프론트 모듈의 scripts와 dependencies를 정의한다.
- Create: `client-admin/index.html`
  - 관리자 앱의 HTML entrypoint를 정의한다.
- Create: `client-admin/src/main.mjs`
  - history/search/status/export 화면을 구성한다.
- Create: `client-admin/src/services/adminApi.mjs`
  - `chat-admin` API client와 `X-Admin-Token` header 처리를 담당한다.
- Create: `client-admin/src/styles.css`
  - 운영자 UI에 맞는 dense dashboard 스타일을 정의한다.
- Create: `client-admin/src/services/adminApi.test.mjs`
  - query parameter, token header, limit handling 같은 API client 동작을 검증한다.
- Create: `client-admin/scripts/build.mjs`
  - 정적 admin UI 산출물을 `client-admin/dist`로 복사한다.
- Create: `chat-persistence/src/main/kotlin/service/AdminChatServiceImpl.kt`
  - RBAC 이후의 관리자 use case orchestration과 audit 기록을 담당한다.
- Create: `chat-persistence/src/main/kotlin/repository/AdminMessageRepository.kt`
  - canonical `chat_messages` 기반 history/search/status SQL을 담당한다.
- Create: `chat-persistence/src/main/kotlin/repository/AdminAuditLogRepository.kt`
  - `admin_audit_logs` insert를 담당한다.
- Create: `chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt`
  - `admin_message_export_jobs` insert/claim/complete/fail을 담당한다.
- Create: `chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt`
  - pending export job을 claim하고 CSV 산출물을 생성한다.
- Modify: `chat-worker-application/src/main/kotlin/com/chat/worker/application/MessageWorkerScheduler.kt`
  - `admin-export` worker role을 scheduler에 연결한다.
- Modify: `chat-persistence/src/main/kotlin/config/ChatWorkerProperties.kt`
  - 기본 worker role 목록에 `admin-export`를 포함한다.
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
  - `chat.admin.*` 운영 설정과 기본 worker role을 문서화된 기본값과 맞춘다.
- Modify: `docker-compose.yml`
  - `CHAT_ADMIN_TOKEN`, `CHAT_ADMIN_EXPORT_DIRECTORY`, `WORKER_ROLES` 기본값을 제공한다.
- Modify: `infra/postgres/message-partitions.sql`
  - `admin_audit_logs`, `admin_message_export_jobs`, admin search index 보강 DDL을 추가한다.
- Modify: `docs/configuration.md`
  - Phase 5 admin/export worker 환경 변수를 문서화한다.
- Create: `scripts/seed-admin-search-messages.mjs`
  - 1천만건까지 생성 가능한 PostgreSQL seed script를 제공한다.
- Modify: `docs/api-reference.md`
  - Phase 5 admin endpoint를 문서화한다.
- Modify: `docs/openapi.yaml`
  - Phase 5 admin endpoint schema를 문서화한다.

## Task 1: 관리자 API 계약과 RBAC RED

**Files:**
- Create: `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminChatControllerTest.kt`
- Create: `chat-domain/src/main/kotlin/dto/AdminDto.kt`
- Create: `chat-domain/src/main/kotlin/service/AdminChatService.kt`
- Create: `chat-admin/src/main/kotlin/com/chat/admin/config/AdminProperties.kt`
- Create: `chat-admin/src/main/kotlin/com/chat/admin/security/AdminTokenVerifier.kt`
- Create: `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminChatController.kt`

- [x] **Step 1: Write failing controller tests**

```kotlin
@Test
fun `관리자 history 조회는 token을 검증하고 bounded limit으로 service를 호출한다`() {
    mockMvc.get("/admin/chat-rooms/10/messages") {
        header("X-Admin-Token", "local-admin-token")
        param("from", "2026-06-14T00:00:00")
        param("to", "2026-06-15T00:00:00")
        param("limit", "-5")
    }.andExpect { status { isOk() } }

    assertEquals(1, service.historyRequest?.limit)
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon
```

Expected: compile fails because `AdminChatController`, `AdminChatService`, and admin DTOs do not exist.

- [x] **Step 3: Implement controller contract**

Add `AdminChatController`, `AdminProperties`, `AdminTokenVerifier`, `AdminChatService`, and DTOs needed by the tests.

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon
```

Expected: tests pass.

## Task 2: canonical admin repository RED/GREEN

**Files:**
- Create: `chat-persistence/src/test/kotlin/repository/AdminMessageRepositoryTest.kt`
- Create: `chat-persistence/src/main/kotlin/repository/AdminMessageRepository.kt`

- [x] **Step 1: Write failing repository tests**

```kotlin
@Test
fun `history query는 roomId와 created_at 범위와 cursor를 사용한다`() {
    val jdbcTemplate = mock(JdbcTemplate::class.java)
    val repository = AdminMessageRepository(jdbcTemplate)

    repository.findRoomMessages(
        roomId = 10L,
        from = LocalDateTime.parse("2026-06-14T00:00:00"),
        to = LocalDateTime.parse("2026-06-15T00:00:00"),
        cursor = 100L,
        limit = 50,
    )

    verify(jdbcTemplate).query(contains("cm.room_id = ?"), any<RowMapper<AdminMessageDto>>(), eq(10L), any(), any(), eq(100L), eq(50))
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminMessageRepositoryTest --no-daemon
```

Expected: compile fails because `AdminMessageRepository` does not exist.

- [x] **Step 3: Implement SQL repository**

Implement `findRoomMessages`, `searchMessages`, and `findRoomStatus` using parameterized `JdbcTemplate` SQL.

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminMessageRepositoryTest --no-daemon
```

Expected: tests pass.

## Task 3: audit log와 export job RED/GREEN

**Files:**
- Create: `chat-persistence/src/test/kotlin/repository/AdminAuditLogRepositoryTest.kt`
- Create: `chat-persistence/src/test/kotlin/repository/AdminExportJobRepositoryTest.kt`
- Create: `chat-persistence/src/main/kotlin/repository/AdminAuditLogRepository.kt`
- Create: `chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt`
- Modify: `infra/postgres/message-partitions.sql`

- [x] **Step 1: Write failing repository tests**

```kotlin
@Test
fun `audit log insert는 actor action target metadata를 바인딩한다`() {
    val jdbcTemplate = mock(JdbcTemplate::class.java)
    val repository = AdminAuditLogRepository(jdbcTemplate)

    repository.record(
        actor = "admin-local",
        action = "ADMIN_MESSAGE_SEARCH",
        targetType = "MESSAGE",
        targetId = "room:10",
        metadataJson = """{"query":"hello"}""",
    )

    verify(jdbcTemplate).update(contains("INSERT INTO admin_audit_logs"), eq("admin-local"), eq("ADMIN_MESSAGE_SEARCH"), eq("MESSAGE"), eq("room:10"), eq("""{"query":"hello"}"""))
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminAuditLogRepositoryTest --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --no-daemon
```

Expected: compile fails because repositories do not exist.

- [x] **Step 3: Implement repositories and DDL**

Add `admin_audit_logs` and `admin_message_export_jobs` DDL to `infra/postgres/message-partitions.sql`, then implement insert methods.

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminAuditLogRepositoryTest --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --no-daemon
```

Expected: tests pass.

## Task 4: AdminChatService orchestration RED/GREEN

**Files:**
- Create: `chat-persistence/src/test/kotlin/service/AdminChatServiceImplTest.kt`
- Create: `chat-persistence/src/main/kotlin/service/AdminChatServiceImpl.kt`

- [x] **Step 1: Write failing service tests**

```kotlin
@Test
fun `search는 repository 결과와 latency를 반환하고 audit log를 남긴다`() {
    val service = AdminChatServiceImpl(messageRepository, auditRepository, exportJobRepository)

    val response = service.searchMessages(
        actor = "admin-local",
        request = AdminMessageSearchRequest(query = "hello", roomId = 10L, from = null, to = null, senderId = null, cursor = null, limit = 50),
    )

    assertEquals("hello", response.query)
    assertTrue(response.latencyMs >= 0)
    assertEquals("ADMIN_MESSAGE_SEARCH", auditRepository.actions.single())
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon
```

Expected: compile fails because `AdminChatServiceImpl` does not exist.

- [x] **Step 3: Implement service**

Implement history, search, room status, and export job creation orchestration with audit records.

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon
```

Expected: tests pass.

## Task 5: client-admin 프론트 모듈과 API 문서

**Files:**
- Create: `client-admin/package.json`
- Create: `client-admin/index.html`
- Create: `client-admin/src/main.mjs`
- Create: `client-admin/src/services/adminApi.mjs`
- Create: `client-admin/src/styles.css`
- Create: `client-admin/src/services/adminApi.test.mjs`
- Create: `client-admin/scripts/build.mjs`
- Modify: `docs/api-reference.md`
- Modify: `docs/openapi.yaml`

- [x] **Step 1: Write client-admin API client test**

```typescript
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildAdminSearchUrl } from '../services/adminApi.ts';

test('admin search URL includes query filters and bounded limit', () => {
  const url = buildAdminSearchUrl('/api', {
    query: 'hello',
    roomId: 10,
    senderId: 7,
    limit: 500,
  });

  assert.equal(url, '/api/admin/messages/search?q=hello&roomId=10&senderId=7&limit=100');
});
```

- [x] **Step 2: Verify RED**

Run:

```bash
cd client-admin && npm run test:unit
```

Expected: fails because `client-admin` and `buildAdminSearchUrl` do not exist yet.

- [x] **Step 3: Add client-admin module**

Provide a dense operator UI that calls:

```text
GET /api/admin/chat-rooms/{roomId}/messages
GET /api/admin/messages/search
GET /api/admin/rooms/{roomId}/status
POST /api/admin/exports/messages
```

- [x] **Step 4: Verify client-admin**

Run:

```bash
cd client-admin && npm run test:unit && npm run build
```

Expected: unit tests and production build pass.

- [x] **Step 5: Add API docs**

Document headers, query parameters, response schemas, and export job response.

- [x] **Step 6: Verify docs**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

## Task 6: 1천만건 seed/load script

**Files:**
- Create: `scripts/seed-admin-search-messages.mjs`

- [x] **Step 1: Write script**

The script must support:

```bash
node scripts/seed-admin-search-messages.mjs --messages 10000000 --rooms normal:60,hot:30,very-hot:10
```

- [x] **Step 2: Validate parameters**

The script must reject non-positive `--messages` and malformed `--rooms`.

- [x] **Step 3: Provide dry run**

The script must support:

```bash
node scripts/seed-admin-search-messages.mjs --messages 1000 --rooms normal:1 --dry-run
```

Expected: prints seed plan without writing to PostgreSQL.

## Task 7: admin export worker role RED/GREEN

**Files:**
- Create: `chat-persistence/src/test/kotlin/service/AdminMessageExportWorkerTest.kt`
- Modify: `chat-persistence/src/test/kotlin/repository/AdminExportJobRepositoryTest.kt`
- Modify: `chat-worker-application/src/test/kotlin/com/chat/worker/application/MessageWorkerSchedulerTest.kt`
- Create: `chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt`
- Modify: `chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt`
- Modify: `chat-worker-application/src/main/kotlin/com/chat/worker/application/MessageWorkerScheduler.kt`
- Modify: `chat-persistence/src/main/kotlin/config/ChatWorkerProperties.kt`
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Modify: `docker-compose.yml`
- Modify: `docs/configuration.md`

- [x] **Step 1: Write failing worker role tests**

Add scheduler coverage for:

```kotlin
@Test
fun `admin-export role이 켜져 있으면 export worker를 poll한다`() {
    val scheduler = MessageWorkerScheduler(
        workerProperties = ChatWorkerProperties(roles = setOf("admin-export")),
        messageWriterWorker = writerWorker,
        hotRoomFanoutWorker = fanoutWorker,
        adminMessageExportWorker = exportWorker,
    )

    scheduler.pollAdminExport()

    verify(exportWorker).pollAndExport()
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :chat-worker-application:test --tests com.chat.worker.application.MessageWorkerSchedulerTest --no-daemon
```

Expected: compile fails because `AdminMessageExportWorker` and `pollAdminExport` do not exist.

- [x] **Step 3: Implement worker and repository state transitions**

Use `FOR UPDATE SKIP LOCKED` claim semantics so multiple `admin-export` worker instances do not process the same job.

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :chat-worker-application:test --tests com.chat.worker.application.MessageWorkerSchedulerTest --no-daemon
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --tests com.chat.persistence.service.AdminMessageExportWorkerTest --no-daemon
```

Expected: tests pass.

## Final Verification

Run:

```bash
./gradlew test --no-daemon
git diff --check
cd client-admin && npm run test:unit && npm run build
node scripts/seed-admin-search-messages.mjs --messages 1000 --rooms normal:1 --dry-run
```

When local Docker Compose is available, also run:

```bash
mise run start:all
curl -H "X-Admin-Token: local-admin-token" "http://localhost/api/admin/chat-rooms/1/messages?limit=10"
curl -H "X-Admin-Token: local-admin-token" "http://localhost/api/admin/messages/search?q=hello&limit=10"
cd client-admin && npm run build
node scripts/seed-admin-search-messages.mjs --messages 10000000 --rooms normal:60,hot:30,very-hot:10
```

## Complexity

- 관리자 history 조회: `O(log N_room_time + L)`, `L`은 반환 메시지 수다.
- 관리자 keyword search: PostgreSQL GIN index 사용 시 `O(log N_text + L)`에 가깝고, ranking/filter 조합에 따라 partition pruning 효과가 달라진다.
- export job 생성: `O(1)`.
- export job claim: pending job index 기준 `O(log J)`, `J`는 pending job 수다.
- export CSV 생성: `O(E)` time, `O(E)` memory, `E`는 현재 worker export 최대 row 수다.
- audit insert: `O(1)`.
- seed script: `O(M)` time, `O(B)` memory, `M`은 생성 메시지 수, `B`는 batch size다.

## Caveats

> - PostgreSQL FTS/trigram으로 p95 1초 SLA를 만족하지 못하면 OpenSearch 도입을 Phase 5 이후 별도 결정으로 검토한다.
> - `X-Admin-Token`은 Phase 5의 최소 RBAC다. 운영 전에는 SSO/OIDC 또는 별도 admin identity provider로 교체할 수 있다.
> - `client-admin`은 일반 사용자용 `client`와 배포 단위가 다르다. Nginx/배포 스크립트에서 admin UI 정적 파일 serving 위치는 운영 환경에 맞게 별도 hardening할 수 있다.
> - 1천만건 seed는 로컬 머신과 Docker 볼륨 성능에 따라 오래 걸릴 수 있으므로 dry run과 batch size 조절을 제공한다.
> - export worker는 Phase 5에서 CSV 파일 산출물까지 생성한다. 대용량 파일 업로드, object storage 연동, chunked export는 운영 인프라 확정 뒤 확장한다.
