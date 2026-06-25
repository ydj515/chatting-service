# Phase 8.3 Object Storage + Cold Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add S3-compatible Object Storage support for admin exports and PostgreSQL cold archives, using MinIO in Docker Compose and stable `s3://` object URIs with short-lived download URLs.

**Architecture:** `chat-persistence` owns an `ObjectStoragePort` and S3-compatible adapter. Admin export keeps local staging files for checkpoint/resume, uploads the final CSV to Object Storage, stores a stable `s3://bucket/key`, and serves presigned URLs through a new export status API. PostgreSQL partition archive keeps its shell worker boundary but uses an archive image with AWS CLI to upload CSV and metadata before any detach/drop.

**Tech Stack:** Kotlin, Spring Boot configuration properties, AWS SDK for Java v2 S3 client/presigner, PostgreSQL shell archive script, Docker Compose, MinIO, Node `node:test` contract tests, React/Vite admin client.

---

## File Structure

- Modify `gradle/libs.versions.toml`: add AWS SDK version and `aws-sdk-s3` library alias.
- Modify `chat-persistence/build.gradle.kts`: add S3 SDK dependency.
- Create `chat-persistence/src/main/kotlin/storage/ObjectStoragePort.kt`: storage port, request/result DTOs, S3 URI parser.
- Create `chat-persistence/src/main/kotlin/storage/S3ObjectStorageAdapter.kt`: AWS SDK S3 upload and presigned URL implementation.
- Create `chat-persistence/src/main/kotlin/config/ChatObjectStorageProperties.kt`: bind `chat.object-storage.*`.
- Create `chat-persistence/src/main/kotlin/config/ObjectStorageConfig.kt`: create S3 client/presigner when enabled and disabled fallback otherwise.
- Create `chat-persistence/src/test/kotlin/storage/S3ObjectStorageAdapterTest.kt`: unit tests for upload URI and presign behavior.
- Modify `chat-domain/src/main/kotlin/dto/AdminDto.kt`: add export status DTO.
- Modify `chat-domain/src/main/kotlin/service/AdminChatService.kt`: add export status lookup method.
- Modify `chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt`: add status record and `findById`.
- Modify `chat-persistence/src/main/kotlin/service/AdminChatServiceImpl.kt`: presign completed export object URI on lookup.
- Modify `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminChatController.kt`: add `GET /admin/exports/{jobId}`.
- Modify `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminChatControllerTest.kt`: cover export status success and 404.
- Modify `chat-persistence/src/test/kotlin/repository/AdminExportJobRepositoryTest.kt`: cover status lookup SQL.
- Modify `chat-persistence/src/test/kotlin/service/AdminChatServiceImplTest.kt`: cover presign/audit/null status.
- Modify `chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt`: upload final CSV and complete with `s3://` URI.
- Modify `chat-persistence/src/test/kotlin/service/AdminMessageExportWorkerTest.kt`: cover final upload, checkpoint file URI, upload failure.
- Modify `chat-runtime-config/src/main/resources/application-docker.yml`: add `chat.object-storage` defaults.
- Modify `docker-compose.yml`: add MinIO, bucket init, Object Storage env, archive build/dependency, volume.
- Modify `.env.example`: add Object Storage envs.
- Modify `mise.toml`: start MinIO in local infra.
- Create `infra/postgres/archive/Dockerfile`: PostgreSQL archive image with AWS CLI.
- Modify `infra/postgres/archive/archive-partitions.sh`: upload CSV/metadata to Object Storage before DROP.
- Create `scripts/lib/phase8ObjectStorageCompose.test.mjs`: Compose/config/archive contract tests.
- Modify `client-admin/src/types/index.ts`: add export status fields.
- Modify `client-admin/src/services/adminApi.ts`: add export status URL/fetcher.
- Modify `client-admin/src/services/adminApi.test.ts`: cover export status URL.
- Modify `client-admin/src/App.tsx`: show last export job, refresh status, and download link.
- Modify `docs/openapi.yaml`: add export status endpoint/schema fields.
- Modify `docs/api-reference.md`, `docs/configuration.md`, `docs/infrastructure.md`, `README.md`: document Object Storage and cold archive flow.

## Task 1: Object Storage Port And S3 Adapter

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `chat-persistence/build.gradle.kts`
- Create: `chat-persistence/src/main/kotlin/storage/ObjectStoragePort.kt`
- Create: `chat-persistence/src/main/kotlin/storage/S3ObjectStorageAdapter.kt`
- Create: `chat-persistence/src/main/kotlin/config/ChatObjectStorageProperties.kt`
- Create: `chat-persistence/src/main/kotlin/config/ObjectStorageConfig.kt`
- Test: `chat-persistence/src/test/kotlin/storage/S3ObjectStorageAdapterTest.kt`

- [ ] **Step 1: Write the failing adapter test**

Create `chat-persistence/src/test/kotlin/storage/S3ObjectStorageAdapterTest.kt`:

```kotlin
package com.chat.persistence.storage

import com.chat.persistence.config.ChatObjectStorageProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.net.URL
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText

class S3ObjectStorageAdapterTest {
    @Test
    fun `uploadFile uploads a local file and returns stable s3 uri`() {
        val s3Client = mock(S3Client::class.java)
        val presigner = mock(S3Presigner::class.java)
        val adapter = adapter(s3Client, presigner)
        val file = Files.createTempFile("export-", ".csv")
        file.writeText("id,message\n1,hello\n")

        val result = adapter.uploadFile(
            ObjectUploadRequest(
                objectKey = "admin-exports/export-1.csv",
                file = file,
                contentType = "text/csv; charset=utf-8",
            ),
        )

        assertEquals("s3://chat-archives/admin-exports/export-1.csv", result.objectUri)
        val requestCaptor = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        val bodyCaptor = ArgumentCaptor.forClass(RequestBody::class.java)
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture())
        assertEquals("chat-archives", requestCaptor.value.bucket())
        assertEquals("admin-exports/export-1.csv", requestCaptor.value.key())
        assertEquals("text/csv; charset=utf-8", requestCaptor.value.contentType())
    }

    @Test
    fun `createDownloadUrl presigns a stable s3 uri with ttl`() {
        val s3Client = mock(S3Client::class.java)
        val presigner = mock(S3Presigner::class.java)
        val presigned = mock(PresignedGetObjectRequest::class.java)
        `when`(presigned.url()).thenReturn(URL("http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc"))
        `when`(presigner.presignGetObject(org.mockito.ArgumentMatchers.any(GetObjectPresignRequest::class.java)))
            .thenReturn(presigned)
        val adapter = adapter(s3Client, presigner)

        val result = adapter.createDownloadUrl(
            objectUri = "s3://chat-archives/admin-exports/export-1.csv",
            ttl = Duration.ofMinutes(15),
        )

        assertEquals("http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc", result.url)
        assertEquals(Instant.parse("2026-06-26T00:15:00Z"), result.expiresAt)
        val requestCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest::class.java)
        verify(presigner).presignGetObject(requestCaptor.capture())
        val getObjectRequest: GetObjectRequest = requestCaptor.value.getObjectRequest()
        assertEquals("chat-archives", getObjectRequest.bucket())
        assertEquals("admin-exports/export-1.csv", getObjectRequest.key())
        assertEquals(Duration.ofMinutes(15), requestCaptor.value.signatureDuration())
    }

    @Test
    fun `parseS3Uri rejects non s3 uri`() {
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ObjectStorageUris.parseS3Uri("file:///tmp/export.csv")
        }

        assertTrue(error.message!!.contains("s3://"))
    }

    private fun adapter(
        s3Client: S3Client,
        presigner: S3Presigner,
    ): S3ObjectStorageAdapter {
        return S3ObjectStorageAdapter(
            s3Client = s3Client,
            presigner = presigner,
            properties = ChatObjectStorageProperties(
                bucket = "chat-archives",
            ),
            clock = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC),
        )
    }
}
```

- [ ] **Step 2: Run the adapter test and verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.storage.S3ObjectStorageAdapterTest --no-daemon
```

Expected: FAIL because `ObjectStoragePort`, `ObjectStorageUris`, `S3ObjectStorageAdapter`, and AWS SDK aliases do not exist.

- [ ] **Step 3: Add dependencies and storage implementation**

Add `awsSdk` to the existing `[versions]` section and `aws-sdk-s3` to the existing `[libraries]` section in `gradle/libs.versions.toml`:

```toml
[versions]
awsSdk = "2.25.70"

[libraries]
aws-sdk-s3 = { group = "software.amazon.awssdk", name = "s3", version.ref = "awsSdk" }
```

Add to `chat-persistence/build.gradle.kts`:

```kotlin
implementation(libs.aws.sdk.s3)
```

Create `chat-persistence/src/main/kotlin/storage/ObjectStoragePort.kt`:

```kotlin
package com.chat.persistence.storage

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

interface ObjectStoragePort {
    fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult

    fun createDownloadUrl(objectUri: String, ttl: Duration): PresignedObjectUrl
}

data class ObjectUploadRequest(
    val objectKey: String,
    val file: Path,
    val contentType: String,
)

data class ObjectUploadResult(
    val objectUri: String,
)

data class PresignedObjectUrl(
    val url: String,
    val expiresAt: Instant,
)

data class S3ObjectLocation(
    val bucket: String,
    val key: String,
)

object ObjectStorageUris {
    fun toS3Uri(bucket: String, key: String): String {
        require(bucket.isNotBlank()) { "bucket must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }
        val encodedKey = key.split("/")
            .joinToString("/") { segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8) }
        return "s3://$bucket/$encodedKey"
    }

    fun parseS3Uri(objectUri: String): S3ObjectLocation {
        val uri = URI.create(objectUri)
        require(uri.scheme == "s3") { "Object URI must use s3:// scheme: $objectUri" }
        val bucket = uri.host.orEmpty()
        val key = uri.rawPath.removePrefix("/")
            .split("/")
            .joinToString("/") { segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8) }
        require(bucket.isNotBlank()) { "Object URI bucket must not be blank: $objectUri" }
        require(key.isNotBlank()) { "Object URI key must not be blank: $objectUri" }
        return S3ObjectLocation(bucket = bucket, key = key)
    }
}
```

Create `chat-persistence/src/main/kotlin/config/ChatObjectStorageProperties.kt`:

```kotlin
package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.object-storage")
data class ChatObjectStorageProperties(
    val enabled: Boolean = false,
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val bucket: String = "chat-archives",
    val accessKey: String = "chatminio",
    val secretKey: String = "chatminiosecret",
    val pathStyleAccess: Boolean = true,
    val adminExportPrefix: String = "admin-exports",
    val presignedUrlTtl: Duration = Duration.ofMinutes(15),
)
```

Create `chat-persistence/src/main/kotlin/storage/S3ObjectStorageAdapter.kt`:

```kotlin
package com.chat.persistence.storage

import com.chat.persistence.config.ChatObjectStorageProperties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Clock
import java.time.Duration

class S3ObjectStorageAdapter(
    private val s3Client: S3Client,
    private val presigner: S3Presigner,
    private val properties: ChatObjectStorageProperties,
    private val clock: Clock = Clock.systemUTC(),
) : ObjectStoragePort {
    override fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult {
        val objectKey = normalizeObjectKey(request.objectKey)
        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .contentType(request.contentType)
            .build()
        s3Client.putObject(putRequest, RequestBody.fromFile(request.file))
        return ObjectUploadResult(ObjectStorageUris.toS3Uri(properties.bucket, objectKey))
    }

    override fun createDownloadUrl(objectUri: String, ttl: Duration): PresignedObjectUrl {
        val location = ObjectStorageUris.parseS3Uri(objectUri)
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(location.bucket)
            .key(location.key)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getObjectRequest)
            .build()
        val presigned = presigner.presignGetObject(presignRequest)
        return PresignedObjectUrl(
            url = presigned.url().toString(),
            expiresAt = clock.instant().plus(ttl),
        )
    }

    private fun normalizeObjectKey(objectKey: String): String {
        return objectKey.trim().trimStart('/').also {
            require(it.isNotBlank()) { "objectKey must not be blank" }
            require(!it.contains("..")) { "objectKey must not contain relative path segments" }
        }
    }
}
```

Create `chat-persistence/src/main/kotlin/config/ObjectStorageConfig.kt`:

```kotlin
package com.chat.persistence.config

import com.chat.persistence.storage.ObjectStoragePort
import com.chat.persistence.storage.ObjectUploadRequest
import com.chat.persistence.storage.ObjectUploadResult
import com.chat.persistence.storage.PresignedObjectUrl
import com.chat.persistence.storage.S3ObjectStorageAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

@Configuration
class ObjectStorageConfig {
    @Bean
    @ConditionalOnProperty(prefix = "chat.object-storage", name = ["enabled"], havingValue = "true")
    fun s3Client(properties: ChatObjectStorageProperties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.pathStyleAccess)
                    .build(),
            )
        properties.endpoint.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    @Bean
    @ConditionalOnProperty(prefix = "chat.object-storage", name = ["enabled"], havingValue = "true")
    fun s3Presigner(properties: ChatObjectStorageProperties): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials(properties))
        properties.endpoint.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    @Bean
    @ConditionalOnProperty(prefix = "chat.object-storage", name = ["enabled"], havingValue = "true")
    fun objectStoragePort(
        s3Client: S3Client,
        s3Presigner: S3Presigner,
        properties: ChatObjectStorageProperties,
    ): ObjectStoragePort {
        return S3ObjectStorageAdapter(s3Client, s3Presigner, properties)
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStoragePort::class)
    fun disabledObjectStoragePort(): ObjectStoragePort {
        return object : ObjectStoragePort {
            override fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult {
                error("Object Storage is disabled")
            }

            override fun createDownloadUrl(objectUri: String, ttl: Duration): PresignedObjectUrl {
                error("Object Storage is disabled")
            }
        }
    }

    private fun credentials(properties: ChatObjectStorageProperties): StaticCredentialsProvider {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
        )
    }
}
```

- [ ] **Step 4: Run the adapter test and verify it passes**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.storage.S3ObjectStorageAdapterTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml chat-persistence/build.gradle.kts \
  chat-persistence/src/main/kotlin/storage/ObjectStoragePort.kt \
  chat-persistence/src/main/kotlin/storage/S3ObjectStorageAdapter.kt \
  chat-persistence/src/main/kotlin/config/ChatObjectStorageProperties.kt \
  chat-persistence/src/main/kotlin/config/ObjectStorageConfig.kt \
  chat-persistence/src/test/kotlin/storage/S3ObjectStorageAdapterTest.kt
git commit -m "feat: add object storage port"
```

## Task 2: Admin Export Status API

**Files:**
- Modify: `chat-domain/src/main/kotlin/dto/AdminDto.kt`
- Modify: `chat-domain/src/main/kotlin/service/AdminChatService.kt`
- Modify: `chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt`
- Modify: `chat-persistence/src/main/kotlin/service/AdminChatServiceImpl.kt`
- Modify: `chat-admin/src/main/kotlin/com/chat/admin/controller/AdminChatController.kt`
- Test: `chat-persistence/src/test/kotlin/repository/AdminExportJobRepositoryTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/AdminChatServiceImplTest.kt`
- Test: `chat-admin/src/test/kotlin/com/chat/admin/controller/AdminChatControllerTest.kt`

- [ ] **Step 1: Write failing repository, service, and controller tests**

Append to `AdminExportJobRepositoryTest`:

```kotlin
@Test
fun `export job status lookup returns output uri and timestamps`() {
    val jdbcTemplate = mock(JdbcTemplate::class.java)
    val repository = AdminExportJobRepository(jdbcTemplate = jdbcTemplate)
    `when`(jdbcTemplate.queryForObject(anyString(), anyExportJobStatusRowMapper(), eq("export-1")))
        .thenReturn(
            AdminExportJobStatusRecord(
                jobId = "export-1",
                actor = "admin-local",
                status = "COMPLETED",
                outputUri = "s3://chat-archives/admin-exports/export-1.csv",
                exportedRows = 2,
                errorMessage = null,
                createdAt = java.time.LocalDateTime.parse("2026-06-26T00:00:00"),
                startedAt = java.time.LocalDateTime.parse("2026-06-26T00:00:01"),
                completedAt = java.time.LocalDateTime.parse("2026-06-26T00:00:02"),
            ),
        )

    val record = repository.findById("export-1")

    assertEquals("COMPLETED", record?.status)
    assertEquals("s3://chat-archives/admin-exports/export-1.csv", record?.outputUri)
    val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
    verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), anyExportJobStatusRowMapper(), eq("export-1"))
    assertTrue(sqlCaptor.value.contains("FROM admin_message_export_jobs"))
    assertTrue(sqlCaptor.value.contains("WHERE job_id = ?"))
}
```

Add this helper to the same test:

```kotlin
private fun anyExportJobStatusRowMapper(): RowMapper<AdminExportJobStatusRecord> {
    any(RowMapper::class.java)
    return uninitialized()
}
```

Append to `AdminChatServiceImplTest`:

```kotlin
@Test
fun `export status 조회는 completed s3 output에 presigned download url을 붙인다`() {
    val fixture = fixture()
    `when`(fixture.exportJobRepository.findById("export-1")).thenReturn(
        AdminExportJobStatusRecord(
            jobId = "export-1",
            actor = "admin-local",
            status = "COMPLETED",
            outputUri = "s3://chat-archives/admin-exports/export-1.csv",
            exportedRows = 2,
            errorMessage = null,
            createdAt = LocalDateTime.parse("2026-06-26T00:00:00"),
            startedAt = LocalDateTime.parse("2026-06-26T00:00:01"),
            completedAt = LocalDateTime.parse("2026-06-26T00:00:02"),
        ),
    )
    fixture.objectStoragePort.nextDownloadUrl = PresignedObjectUrl(
        url = "http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc",
        expiresAt = Instant.parse("2026-06-26T00:15:00Z"),
    )

    val result = fixture.service.getMessageExport("admin-local", "export-1")

    assertEquals("COMPLETED", result?.status)
    assertEquals("s3://chat-archives/admin-exports/export-1.csv", result?.outputUri)
    assertEquals("http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc", result?.downloadUrl)
    assertEquals(Instant.parse("2026-06-26T00:15:00Z"), result?.downloadUrlExpiresAt)
    assertEquals("s3://chat-archives/admin-exports/export-1.csv", fixture.objectStoragePort.presignedObjectUri)
}
```

Append to `AdminChatControllerTest`:

```kotlin
@Test
fun `관리자 export status 조회는 완료 job과 download url을 반환한다`() {
    mockMvc.get("/admin/exports/export-1") {
        header("X-Admin-Token", "local-admin-token")
    }
        .andExpect {
            status { isOk() }
            jsonPath("$.jobId") { value("export-1") }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.outputUri") { value("s3://chat-archives/admin-exports/export-1.csv") }
            jsonPath("$.downloadUrl") { value("http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc") }
            jsonPath("$.exportedRows") { value(2) }
        }

    assertEquals("admin-local", service.exportStatusActor)
    assertEquals("export-1", service.exportStatusJobId)
}

@Test
fun `관리자 export status 조회는 없는 job을 404로 반환한다`() {
    mockMvc.get("/admin/exports/missing") {
        header("X-Admin-Token", "local-admin-token")
    }
        .andExpect {
            status { isNotFound() }
        }
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon
```

Expected: FAIL because export status DTO, repository lookup, service method, controller route, and fake storage helpers do not exist.

- [ ] **Step 3: Implement DTO, repository, service, and controller**

Add to `chat-domain/src/main/kotlin/dto/AdminDto.kt`:

```kotlin
data class AdminExportJobStatusDto(
    val jobId: String,
    val status: String,
    val createdAt: LocalDateTime,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    val exportedRows: Int,
    val outputUri: String?,
    val downloadUrl: String?,
    val downloadUrlExpiresAt: Instant?,
    val errorMessage: String?,
)
```

Add to `AdminChatService`:

```kotlin
fun getMessageExport(actor: String, jobId: String): AdminExportJobStatusDto?
```

Add to `AdminExportJobRepository.kt`:

```kotlin
data class AdminExportJobStatusRecord(
    val jobId: String,
    val actor: String,
    val status: String,
    val outputUri: String?,
    val exportedRows: Int,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
)

fun findById(jobId: String): AdminExportJobStatusRecord? {
    return try {
        jdbcTemplate.queryForObject(
            """
            SELECT
                job_id,
                actor,
                status,
                output_uri,
                exported_rows,
                error_message,
                created_at,
                started_at,
                completed_at
            FROM admin_message_export_jobs
            WHERE job_id = ?
            """.trimIndent(),
            exportJobStatusRowMapper,
            jobId,
        )
    } catch (e: EmptyResultDataAccessException) {
        null
    }
}
```

Add row mapper to the companion object:

```kotlin
val exportJobStatusRowMapper = RowMapper { rs: ResultSet, _: Int ->
    AdminExportJobStatusRecord(
        jobId = rs.getString("job_id"),
        actor = rs.getString("actor"),
        status = rs.getString("status"),
        outputUri = rs.getString("output_uri"),
        exportedRows = rs.getInt("exported_rows"),
        errorMessage = rs.getString("error_message"),
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
        startedAt = rs.getTimestamp("started_at")?.toLocalDateTime(),
        completedAt = rs.getTimestamp("completed_at")?.toLocalDateTime(),
    )
}
```

Update `AdminChatServiceImpl` constructor to include:

```kotlin
private val objectStoragePort: ObjectStoragePort,
private val objectStorageProperties: ChatObjectStorageProperties,
```

Implement:

```kotlin
override fun getMessageExport(actor: String, jobId: String): AdminExportJobStatusDto? {
    val record = exportJobRepository.findById(jobId) ?: return null
    val presigned = record.outputUri
        ?.takeIf { record.status == "COMPLETED" && it.startsWith("s3://") }
        ?.let { objectStoragePort.createDownloadUrl(it, objectStorageProperties.presignedUrlTtl) }
    auditLogRepository.record(
        actor = actor,
        action = "ADMIN_MESSAGE_EXPORT_VIEWED",
        targetType = "EXPORT_JOB",
        targetId = jobId,
        metadataJson = """{"jobId":"$jobId","status":"${record.status}"}""",
    )
    return AdminExportJobStatusDto(
        jobId = record.jobId,
        status = record.status,
        createdAt = record.createdAt,
        startedAt = record.startedAt,
        completedAt = record.completedAt,
        exportedRows = record.exportedRows,
        outputUri = record.outputUri,
        downloadUrl = presigned?.url,
        downloadUrlExpiresAt = presigned?.expiresAt,
        errorMessage = record.errorMessage,
    )
}
```

Add to `AdminChatController`:

```kotlin
@GetMapping("/exports/{jobId}")
fun getMessageExport(
    @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
    @PathVariable jobId: String,
): AdminExportJobStatusDto {
    val actor = adminTokenVerifier.requireActor(adminToken)
    return adminChatService.getMessageExport(actor = actor, jobId = jobId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "export job not found")
}
```

- [ ] **Step 4: Run the focused tests and verify they pass**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-domain/src/main/kotlin/dto/AdminDto.kt \
  chat-domain/src/main/kotlin/service/AdminChatService.kt \
  chat-persistence/src/main/kotlin/repository/AdminExportJobRepository.kt \
  chat-persistence/src/main/kotlin/service/AdminChatServiceImpl.kt \
  chat-admin/src/main/kotlin/com/chat/admin/controller/AdminChatController.kt \
  chat-persistence/src/test/kotlin/repository/AdminExportJobRepositoryTest.kt \
  chat-persistence/src/test/kotlin/service/AdminChatServiceImplTest.kt \
  chat-admin/src/test/kotlin/com/chat/admin/controller/AdminChatControllerTest.kt
git commit -m "feat: add admin export status api"
```

## Task 3: Admin Export Final Object Upload

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt`
- Test: `chat-persistence/src/test/kotlin/service/AdminMessageExportWorkerTest.kt`

- [ ] **Step 1: Write failing export worker tests**

Update worker construction in `AdminMessageExportWorkerTest` to pass a fake `ObjectStoragePort` and `ChatObjectStorageProperties`. Add a helper:

```kotlin
private class RecordingObjectStoragePort : ObjectStoragePort {
    var uploadedFile: Path? = null
    var uploadedObjectKey: String? = null
    var uploadedContentType: String? = null
    var failUpload: Boolean = false

    override fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult {
        if (failUpload) {
            error("upload failed")
        }
        uploadedFile = request.file
        uploadedObjectKey = request.objectKey
        uploadedContentType = request.contentType
        return ObjectUploadResult("s3://chat-archives/${request.objectKey}")
    }

    override fun createDownloadUrl(objectUri: String, ttl: Duration): PresignedObjectUrl {
        return PresignedObjectUrl("http://localhost/download", Instant.parse("2026-06-26T00:15:00Z"))
    }
}
```

Change the first test assertion from `file://` completion to final object URI:

```kotlin
val storage = RecordingObjectStoragePort()
val worker = worker(exportJobRepository, messageRepository, tempDir, objectStoragePort = storage)

val outputCaptor = ArgumentCaptor.forClass(String::class.java)
verify(exportJobRepository).markCompleted(eqString("export-1"), captureString(outputCaptor))
assertEquals("s3://chat-archives/admin-exports/export-1.csv", outputCaptor.value)
assertEquals("admin-exports/export-1.csv", storage.uploadedObjectKey)
assertEquals("text/csv; charset=utf-8", storage.uploadedContentType)
assertTrue(Files.readString(storage.uploadedFile, Charsets.UTF_8).contains("msg-1"))
```

Add upload failure behavior:

```kotlin
@Test
fun `export worker는 object storage upload 실패 시 job을 failed로 전이한다`(@TempDir tempDir: Path) {
    val exportJobRepository = mock(AdminExportJobRepository::class.java)
    val messageRepository = mock(AdminMessageRepository::class.java)
    val storage = RecordingObjectStoragePort().apply { failUpload = true }
    val worker = worker(exportJobRepository, messageRepository, tempDir, objectStoragePort = storage)
    `when`(exportJobRepository.claimNextPending("worker-1")).thenReturn(
        AdminExportJobRecord(
            jobId = "export-1",
            actor = "admin-local",
            requestJson = """{"roomId":10,"from":null,"to":null,"query":null,"senderId":null}""",
        ),
    )
    `when`(messageRepository.findRoomMessages(10L, null, null, null, 2))
        .thenReturn(listOf(message()))

    val exportedRows = worker.pollAndExport()

    assertEquals(0, exportedRows)
    verify(exportJobRepository).markFailed(eqString("export-1"), eqString("upload failed"))
}
```

- [ ] **Step 2: Run the worker test and verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminMessageExportWorkerTest --no-daemon
```

Expected: FAIL because worker still completes with `file://` and does not call Object Storage.

- [ ] **Step 3: Implement final upload**

Update `AdminMessageExportWorker` constructor:

```kotlin
private val objectStoragePort: ObjectStoragePort,
private val objectStorageProperties: ChatObjectStorageProperties,
```

Change `pollAndExport` completion:

```kotlin
val exportResult = writeCsv(job, request)
val upload = objectStoragePort.uploadFile(
    ObjectUploadRequest(
        objectKey = adminExportObjectKey(job.jobId),
        file = exportResult.outputPath,
        contentType = "text/csv; charset=utf-8",
    ),
)
exportJobRepository.markCompleted(job.jobId, upload.objectUri)
```

Update `ExportResult`:

```kotlin
private data class ExportResult(
    val outputPath: Path,
    val outputUri: String,
    val exportedRows: Int,
)
```

Return `outputPath = output` from `writeCsv`.

Add object key helper:

```kotlin
private fun adminExportObjectKey(jobId: String): String {
    val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val prefix = objectStorageProperties.adminExportPrefix.trim().trim('/')
    return if (prefix.isBlank()) "$safeJobId.csv" else "$prefix/$safeJobId.csv"
}
```

Do not change checkpoint updates: they must continue to store the local `file://` staging URI while the job is `RUNNING`.

- [ ] **Step 4: Run worker tests and verify they pass**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.AdminMessageExportWorkerTest --no-daemon
```

Expected: PASS, including resume checkpoint tests.

- [ ] **Step 5: Commit**

```bash
git add chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt \
  chat-persistence/src/test/kotlin/service/AdminMessageExportWorkerTest.kt
git commit -m "feat: upload admin exports to object storage"
```

## Task 4: Compose MinIO And Runtime Configuration

**Files:**
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `mise.toml`
- Test: `scripts/lib/phase8ObjectStorageCompose.test.mjs`

- [ ] **Step 1: Write failing Compose/config contract tests**

Create `scripts/lib/phase8ObjectStorageCompose.test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8');
const dockerConfig = readFileSync(
  new URL('../../chat-runtime-config/src/main/resources/application-docker.yml', import.meta.url),
  'utf8',
);
const envExample = readFileSync(new URL('../../.env.example', import.meta.url), 'utf8');
const mise = readFileSync(new URL('../../mise.toml', import.meta.url), 'utf8');

function serviceBlock(name) {
  const match = compose.match(
    new RegExp(`\\n  ${name}:\\n([\\s\\S]*?)(?=\\n  [a-zA-Z0-9_-]+:\\n|\\n# 볼륨 정의|\\nvolumes:)`),
  );
  assert.ok(match, `service ${name} should exist`);
  return match[1];
}

test('Compose includes loopback-bound MinIO and bucket init gate', () => {
  const minio = serviceBlock('minio');
  assert.match(minio, /minio\/minio:/);
  assert.match(minio, /server \/data --console-address ":9001"/);
  assert.match(minio, /127\.0\.0\.1:\$\{MINIO_API_PORT:-9000\}:9000/);
  assert.match(minio, /127\.0\.0\.1:\$\{MINIO_CONSOLE_PORT:-9001\}:9001/);
  assert.match(minio, /minio_data:\/data/);

  const init = serviceBlock('minio-init');
  assert.match(init, /minio\/mc:/);
  assert.match(init, /mc alias set chat-minio/);
  assert.match(init, /mc mb --ignore-existing/);
  assert.match(compose, /minio_data:/);
});

test('Docker profile exposes object storage properties to Spring apps', () => {
  assert.match(compose, /CHAT_OBJECT_STORAGE_ENABLED:/);
  assert.match(compose, /CHAT_OBJECT_STORAGE_ENDPOINT: \$\{CHAT_OBJECT_STORAGE_ENDPOINT:-http:\/\/minio:9000\}/);
  assert.match(compose, /CHAT_OBJECT_STORAGE_BUCKET:/);
  assert.match(compose, /CHAT_OBJECT_STORAGE_PRESIGNED_URL_TTL:/);
  assert.match(dockerConfig, /object-storage:/);
  assert.match(dockerConfig, /enabled: \$\{CHAT_OBJECT_STORAGE_ENABLED:true\}/);
  assert.match(dockerConfig, /endpoint: \$\{CHAT_OBJECT_STORAGE_ENDPOINT:http:\/\/localhost:9000\}/);
});

test('worker and admin app wait for the MinIO init gate', () => {
  for (const service of ['chat-worker-app-1', 'chat-admin-app-1']) {
    const block = serviceBlock(service);
    assert.match(block, /minio-init: \{ condition: service_completed_successfully \}/);
  }
});

test('local infra starts MinIO and env example documents object storage variables', () => {
  assert.match(mise, /minio minio-init/);
  assert.match(envExample, /CHAT_OBJECT_STORAGE_ENABLED=true/);
  assert.match(envExample, /CHAT_OBJECT_STORAGE_ENDPOINT=http:\/\/minio:9000/);
  assert.match(envExample, /CHAT_OBJECT_STORAGE_BUCKET=chat-archives/);
  assert.match(envExample, /MINIO_ROOT_USER=/);
  assert.match(envExample, /MINIO_ROOT_PASSWORD=/);
});
```

- [ ] **Step 2: Run Node contract test and verify it fails**

Run:

```bash
node --test scripts/lib/phase8ObjectStorageCompose.test.mjs
```

Expected: FAIL because MinIO, object-storage config, and env docs are missing.

- [ ] **Step 3: Add runtime configuration and Compose services**

Add to `application-docker.yml` under `chat`:

```yaml
  object-storage:
    enabled: ${CHAT_OBJECT_STORAGE_ENABLED:true}
    endpoint: ${CHAT_OBJECT_STORAGE_ENDPOINT:http://localhost:9000}
    region: ${CHAT_OBJECT_STORAGE_REGION:us-east-1}
    bucket: ${CHAT_OBJECT_STORAGE_BUCKET:chat-archives}
    access-key: ${CHAT_OBJECT_STORAGE_ACCESS_KEY:chatminio}
    secret-key: ${CHAT_OBJECT_STORAGE_SECRET_KEY:chatminiosecret}
    path-style-access: ${CHAT_OBJECT_STORAGE_PATH_STYLE_ACCESS:true}
    admin-export-prefix: ${CHAT_OBJECT_STORAGE_ADMIN_EXPORT_PREFIX:admin-exports}
    presigned-url-ttl: ${CHAT_OBJECT_STORAGE_PRESIGNED_URL_TTL:15m}
```

Add to `x-chat-app-environment` in `docker-compose.yml`:

```yaml
  CHAT_OBJECT_STORAGE_ENABLED: ${CHAT_OBJECT_STORAGE_ENABLED:-true}
  CHAT_OBJECT_STORAGE_ENDPOINT: ${CHAT_OBJECT_STORAGE_ENDPOINT:-http://minio:9000}
  CHAT_OBJECT_STORAGE_REGION: ${CHAT_OBJECT_STORAGE_REGION:-us-east-1}
  CHAT_OBJECT_STORAGE_BUCKET: ${CHAT_OBJECT_STORAGE_BUCKET:-chat-archives}
  CHAT_OBJECT_STORAGE_ACCESS_KEY: ${CHAT_OBJECT_STORAGE_ACCESS_KEY:-chatminio}
  CHAT_OBJECT_STORAGE_SECRET_KEY: ${CHAT_OBJECT_STORAGE_SECRET_KEY:-chatminiosecret}
  CHAT_OBJECT_STORAGE_PATH_STYLE_ACCESS: ${CHAT_OBJECT_STORAGE_PATH_STYLE_ACCESS:-true}
  CHAT_OBJECT_STORAGE_ADMIN_EXPORT_PREFIX: ${CHAT_OBJECT_STORAGE_ADMIN_EXPORT_PREFIX:-admin-exports}
  CHAT_OBJECT_STORAGE_PRESIGNED_URL_TTL: ${CHAT_OBJECT_STORAGE_PRESIGNED_URL_TTL:-15m}
```

Add services before backend app services:

```yaml
  minio:
    image: ${MINIO_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}
    container_name: chat-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-chatminio}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-chatminiosecret}
    volumes:
      - minio_data:/data
    ports:
      - "127.0.0.1:${MINIO_API_PORT:-9000}:9000"
      - "127.0.0.1:${MINIO_CONSOLE_PORT:-9001}:9001"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:9000/minio/health/ready" ]
      interval: ${MINIO_HEALTHCHECK_INTERVAL:-10s}
      timeout: ${MINIO_HEALTHCHECK_TIMEOUT:-5s}
      retries: ${MINIO_HEALTHCHECK_RETRIES:-5}
    networks:
      - chat-network

  minio-init:
    image: ${MINIO_MC_IMAGE:-minio/mc:RELEASE.2025-04-16T18-13-26Z}
    container_name: chat-minio-init
    entrypoint: [ "/bin/sh", "-c" ]
    command:
      - >
        mc alias set chat-minio http://minio:9000 "$${MINIO_ROOT_USER}" "$${MINIO_ROOT_PASSWORD}" &&
        mc mb --ignore-existing "chat-minio/$${CHAT_OBJECT_STORAGE_BUCKET}"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-chatminio}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-chatminiosecret}
      CHAT_OBJECT_STORAGE_BUCKET: ${CHAT_OBJECT_STORAGE_BUCKET:-chat-archives}
    depends_on:
      minio: { condition: service_healthy }
    networks:
      - chat-network
```

Add `minio-init` dependency to `chat-worker-app-1` and `chat-admin-app-1`:

```yaml
      minio-init: { condition: service_completed_successfully }
```

Add volume:

```yaml
  minio_data:
    driver: local
```

Add `.env.example` object storage section:

```dotenv
# Object Storage / MinIO
CHAT_OBJECT_STORAGE_ENABLED=true
CHAT_OBJECT_STORAGE_ENDPOINT=http://minio:9000
CHAT_OBJECT_STORAGE_REGION=us-east-1
CHAT_OBJECT_STORAGE_BUCKET=chat-archives
CHAT_OBJECT_STORAGE_ACCESS_KEY=chatminio
CHAT_OBJECT_STORAGE_SECRET_KEY=chatminiosecret
CHAT_OBJECT_STORAGE_PATH_STYLE_ACCESS=true
CHAT_OBJECT_STORAGE_ADMIN_EXPORT_PREFIX=admin-exports
CHAT_OBJECT_STORAGE_PRESIGNED_URL_TTL=15m
MINIO_ROOT_USER=chatminio
MINIO_ROOT_PASSWORD=chatminiosecret
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
```

Update `mise.toml` `start:infra` command:

```toml
run = "docker compose --profile dev up -d postgres postgres-primary-setup postgres-replica postgres-partition-archive redis minio minio-init"
```

- [ ] **Step 4: Run contract and Compose config verification**

Run:

```bash
node --test scripts/lib/phase8ObjectStorageCompose.test.mjs
docker compose --profile cluster --profile dev config --quiet
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-runtime-config/src/main/resources/application-docker.yml docker-compose.yml .env.example mise.toml scripts/lib/phase8ObjectStorageCompose.test.mjs
git commit -m "feat: add minio object storage compose"
```

## Task 5: PostgreSQL Partition Cold Archive Upload

**Files:**
- Create: `infra/postgres/archive/Dockerfile`
- Modify: `infra/postgres/archive/archive-partitions.sh`
- Modify: `docker-compose.yml`
- Modify: `scripts/lib/phase8ObjectStorageCompose.test.mjs`

- [ ] **Step 1: Extend failing contract tests for archive upload**

Append to `scripts/lib/phase8ObjectStorageCompose.test.mjs`:

```javascript
const archiveScript = readFileSync(
  new URL('../../infra/postgres/archive/archive-partitions.sh', import.meta.url),
  'utf8',
);
const archiveDockerfile = readFileSync(
  new URL('../../infra/postgres/archive/Dockerfile', import.meta.url),
  'utf8',
);

test('partition archive service uses an image with AWS CLI and waits for MinIO init', () => {
  const block = serviceBlock('postgres-partition-archive');
  assert.match(block, /build:\n\s+context: \./);
  assert.match(block, /dockerfile: infra\/postgres\/archive\/Dockerfile/);
  assert.match(block, /CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED:/);
  assert.match(block, /CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX:/);
  assert.match(block, /AWS_ACCESS_KEY_ID:/);
  assert.match(block, /AWS_SECRET_ACCESS_KEY:/);
  assert.match(block, /minio-init: \{ condition: service_completed_successfully \}/);
  assert.match(archiveDockerfile, /FROM postgres:17\.9-alpine/);
  assert.match(archiveDockerfile, /apk add --no-cache aws-cli/);
});

test('archive script uploads csv and metadata before allowing drop', () => {
  assert.match(archiveScript, /CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED/);
  assert.match(archiveScript, /CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX/);
  assert.match(archiveScript, /aws --endpoint-url/);
  assert.match(archiveScript, /s3 cp "\$archive_file"/);
  assert.match(archiveScript, /s3 cp "\$metadata_file"/);
  assert.match(archiveScript, /Object Storage upload is required before detach\/drop/);
  assert.match(archiveScript, /objectUri/);
  assert.match(archiveScript, /metadataObjectUri/);
  assert.match(archiveScript, /uploadedAt/);
});
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
node --test scripts/lib/phase8ObjectStorageCompose.test.mjs
```

Expected: FAIL because archive Dockerfile and upload logic do not exist.

- [ ] **Step 3: Add archive image and script upload guard**

Create `infra/postgres/archive/Dockerfile`:

```dockerfile
FROM postgres:17.9-alpine

RUN apk add --no-cache aws-cli
```

Update `postgres-partition-archive` in Compose:

```yaml
    image: ${POSTGRES_ARCHIVE_IMAGE:-chat-postgres-partition-archive:local}
    build:
      context: .
      dockerfile: infra/postgres/archive/Dockerfile
```

Add environment:

```yaml
      CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED: ${CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED:-true}
      CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX: ${CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX:-postgres/archive/chat_messages}
      CHAT_OBJECT_STORAGE_ENDPOINT: ${CHAT_OBJECT_STORAGE_ENDPOINT:-http://minio:9000}
      CHAT_OBJECT_STORAGE_BUCKET: ${CHAT_OBJECT_STORAGE_BUCKET:-chat-archives}
      CHAT_OBJECT_STORAGE_REGION: ${CHAT_OBJECT_STORAGE_REGION:-us-east-1}
      AWS_ACCESS_KEY_ID: ${CHAT_OBJECT_STORAGE_ACCESS_KEY:-chatminio}
      AWS_SECRET_ACCESS_KEY: ${CHAT_OBJECT_STORAGE_SECRET_KEY:-chatminiosecret}
      AWS_DEFAULT_REGION: ${CHAT_OBJECT_STORAGE_REGION:-us-east-1}
      AWS_EC2_METADATA_DISABLED: "true"
```

Add dependency:

```yaml
      minio-init: { condition: service_completed_successfully }
```

Modify `archive-partitions.sh` near env setup:

```sh
object_storage_enabled="${CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED:-false}"
object_storage_endpoint="${CHAT_OBJECT_STORAGE_ENDPOINT:-}"
object_storage_bucket="${CHAT_OBJECT_STORAGE_BUCKET:-}"
object_storage_prefix="${CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX:-postgres/archive/chat_messages}"
```

Add functions before `archive_once`:

```sh
is_true() {
  [ "$1" = "true" ] || [ "$1" = "1" ] || [ "$1" = "yes" ]
}

object_key_for() {
  basename="$1"
  prefix="$(printf "%s" "$object_storage_prefix" | sed 's#^/*##; s#/*$##')"
  if [ -z "$prefix" ]; then
    printf "%s" "$basename"
  else
    printf "%s/%s" "$prefix" "$basename"
  fi
}

require_object_storage_for_drop() {
  if is_true "$drop_after_copy" && ! is_true "$object_storage_enabled"; then
    echo "Object Storage upload is required before detach/drop. Enable CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED=true." >&2
    exit 1
  fi
}

upload_archive_file() {
  local_file="$1"
  object_key="$2"
  if ! is_true "$object_storage_enabled"; then
    return 0
  fi
  if [ -z "$object_storage_endpoint" ] || [ -z "$object_storage_bucket" ]; then
    echo "Object Storage endpoint and bucket are required when archive upload is enabled." >&2
    exit 1
  fi
  aws --endpoint-url "$object_storage_endpoint" s3 cp "$local_file" "s3://${object_storage_bucket}/${object_key}"
}
```

Call `require_object_storage_for_drop` after `mkdir -p "$archive_dir"`.

Inside each partition loop, compute object URIs before writing metadata:

```sh
archive_object_key="$(object_key_for "$(basename "$archive_file")")"
metadata_object_key="$(object_key_for "$(basename "$metadata_file")")"
archive_object_uri="s3://${object_storage_bucket}/${archive_object_key}"
metadata_object_uri="s3://${object_storage_bucket}/${metadata_object_key}"
uploaded_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
```

Write metadata JSON with object fields:

```json
  "objectUri": "${archive_object_uri}",
  "metadataObjectUri": "${metadata_object_uri}",
  "uploadedAt": "${uploaded_at}"
```

Upload before drop:

```sh
upload_archive_file "$archive_file" "$archive_object_key"
upload_archive_file "$metadata_file" "$metadata_object_key"
```

Ensure the detach/drop block stays after both upload calls.

- [ ] **Step 4: Run shell and Compose verification**

Run:

```bash
sh -n infra/postgres/archive/archive-partitions.sh
node --test scripts/lib/phase8ObjectStorageCompose.test.mjs
docker compose --profile cluster --profile dev config --quiet
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/postgres/archive/Dockerfile infra/postgres/archive/archive-partitions.sh docker-compose.yml scripts/lib/phase8ObjectStorageCompose.test.mjs
git commit -m "feat: add partition cold archive upload"
```

## Task 6: Admin Client, OpenAPI, And Documentation

**Files:**
- Modify: `client-admin/src/types/index.ts`
- Modify: `client-admin/src/services/adminApi.ts`
- Modify: `client-admin/src/services/adminApi.test.ts`
- Modify: `client-admin/src/App.tsx`
- Modify: `docs/openapi.yaml`
- Modify: `docs/api-reference.md`
- Modify: `docs/configuration.md`
- Modify: `docs/infrastructure.md`
- Modify: `README.md`

- [ ] **Step 1: Write failing admin client URL test**

Update `client-admin/src/services/adminApi.test.ts` imports:

```typescript
import {
  buildAdminExportStatusUrl,
  buildAdminHistoryUrl,
  buildAdminSearchUrl,
  createAdminHeaders,
} from './adminApi.ts';
```

Add:

```typescript
test('admin export status URL encodes job id', () => {
  const url = buildAdminExportStatusUrl('/api/', 'export/with symbols');

  assert.equal(url, '/api/admin/exports/export%2Fwith%20symbols');
});
```

- [ ] **Step 2: Run client unit test and verify failure**

Run:

```bash
cd client-admin && npm run test:unit
```

Expected: FAIL because `buildAdminExportStatusUrl` does not exist.

- [ ] **Step 3: Implement client export status helpers and minimal UI**

Update `client-admin/src/types/index.ts`:

```typescript
export interface AdminExportJob {
  jobId: string;
  status: string;
  createdAt?: string;
  startedAt?: string | null;
  completedAt?: string | null;
  exportedRows?: number;
  outputUri?: string | null;
  downloadUrl?: string | null;
  downloadUrlExpiresAt?: string | null;
  errorMessage?: string | null;
}
```

Add to `adminApi.ts`:

```typescript
export function buildAdminExportStatusUrl(baseUrl: string, jobId: string): string {
  return `${normalizeBaseUrl(baseUrl)}/admin/exports/${encodeURIComponent(jobId)}`;
}

export async function fetchAdminExportStatus(
  baseUrl: string,
  adminToken: string,
  jobId: string,
): Promise<AdminExportJob> {
  return requestJson(buildAdminExportStatusUrl(baseUrl, jobId), adminToken);
}
```

Update `App.tsx` imports:

```typescript
import { createAdminExport, fetchAdminExportStatus, fetchAdminHistory, fetchAdminRoomStatus, searchAdminMessages } from './services/adminApi.ts';
import type { AdminExportJob, AdminFilters, AdminMessage, AdminRoomStatus, SearchMode } from './types/index';
```

Add state:

```typescript
const [lastExportJob, setLastExportJob] = useState<AdminExportJob | null>(null);
```

Update export creation:

```typescript
const job = await createAdminExport(effectiveBaseUrl, token, buildFilters());
setLastExportJob(job);
successMessage = `Export ${job.jobId} ${job.status}`;
```

Add refresh handler:

```typescript
const handleExportStatusRefresh = async () => {
  if (!lastExportJob) return;
  const { baseUrl: effectiveBaseUrl } = persistState();
  await run('Export status loaded', async () => {
    const job = await fetchAdminExportStatus(effectiveBaseUrl, token, lastExportJob.jobId);
    setLastExportJob(job);
  });
};
```

Render below action buttons:

```tsx
{lastExportJob && (
  <div className="export-status">
    <span>
      Export <b>{lastExportJob.jobId}</b> · {lastExportJob.status}
    </span>
    {typeof lastExportJob.exportedRows === 'number' && <span>{lastExportJob.exportedRows} rows</span>}
    {lastExportJob.downloadUrl && (
      <a href={lastExportJob.downloadUrl} target="_blank" rel="noreferrer">
        Download
      </a>
    )}
    <button className="secondary" type="button" onClick={handleExportStatusRefresh} disabled={busy}>
      Refresh Export
    </button>
  </div>
)}
```

If CSS is needed, add a compact `.export-status` row in the existing admin stylesheet without introducing nested cards.

- [ ] **Step 4: Update OpenAPI and docs**

Add `GET /admin/exports/{jobId}` to `docs/openapi.yaml`:

```yaml
  /admin/exports/{jobId}:
    get:
      summary: Get admin message export job status
      tags:
        - Admin
      security:
        - adminToken: []
      parameters:
        - name: jobId
          in: path
          required: true
          schema:
            type: string
      responses:
        '200':
          description: Export job status
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AdminExportJobStatusDto'
        '404':
          description: Export job not found
```

Add schema:

```yaml
    AdminExportJobStatusDto:
      type: object
      properties:
        jobId:
          type: string
        status:
          type: string
        createdAt:
          type: string
          format: date-time
        startedAt:
          type: string
          format: date-time
          nullable: true
        completedAt:
          type: string
          format: date-time
          nullable: true
        exportedRows:
          type: integer
        outputUri:
          type: string
          nullable: true
          example: "s3://chat-archives/admin-exports/export-1.csv"
        downloadUrl:
          type: string
          nullable: true
        downloadUrlExpiresAt:
          type: string
          format: date-time
          nullable: true
        errorMessage:
          type: string
          nullable: true
```

Update docs:

- `docs/api-reference.md`: document `GET /api/admin/exports/{jobId}` and presigned URL behavior.
- `docs/configuration.md`: add Object Storage, MinIO, archive upload env vars.
- `docs/infrastructure.md`: add MinIO startup and cold archive verification commands.
- `README.md`: update infrastructure feature list to mention Object Storage.

- [ ] **Step 5: Run client and docs contract checks**

Run:

```bash
cd client-admin && npm run test:unit && npm run build
node --test scripts/lib/openapiAdminContract.test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add client-admin/src/types/index.ts client-admin/src/services/adminApi.ts client-admin/src/services/adminApi.test.ts client-admin/src/App.tsx \
  docs/openapi.yaml docs/api-reference.md docs/configuration.md docs/infrastructure.md README.md
git commit -m "feat: surface admin export download urls"
```

## Task 7: Full Verification

**Files:**
- No new source files expected. This task validates the whole branch.

- [ ] **Step 1: Run backend focused tests**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.storage.S3ObjectStorageAdapterTest --tests com.chat.persistence.service.AdminMessageExportWorkerTest --tests com.chat.persistence.repository.AdminExportJobRepositoryTest --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon
./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run full backend tests**

Run:

```bash
./gradlew test --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run Node and client checks**

Run:

```bash
node --test scripts/lib/*.test.mjs scripts/*.test.mjs
cd client-admin && npm run test:unit && npm run build
```

Expected: all tests pass and Vite build succeeds.

- [ ] **Step 4: Run infrastructure checks**

Run:

```bash
sh -n infra/postgres/archive/archive-partitions.sh
docker compose --profile cluster --profile dev config --quiet
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 5: Optional heavy smoke**

Run only when Docker resources are available:

```bash
docker compose up -d minio minio-init
docker compose ps minio minio-init
```

Expected: `minio` healthy and `minio-init` completed successfully. This does not replace full end-to-end export/archive validation.

## Spec Coverage Review

- MinIO Compose service: Task 4.
- S3-compatible `ObjectStoragePort`: Task 1.
- Admin export final Object Storage upload: Task 3.
- Stable object URI plus expiring download URL: Task 2 and Task 6.
- Partition DROP before cold archive prevention: Task 5.
- Runtime env and documentation: Task 4 and Task 6.
- Tests and Compose validation: Task 1 through Task 7.

## Caveats

> - Task 3 intentionally keeps local staging for `RUNNING` export jobs. Object Storage resume from uploaded parts remains outside Phase 8.3.
> - Task 5 uses AWS CLI inside the archive image because the archive worker is a shell/PostgreSQL boundary, not a Spring application boundary.
> - Full e2e export and archive upload against live MinIO can be expensive in the local Docker stack. The required gate is unit/contract/Compose config; live smoke is optional unless explicitly requested.

## Alternatives

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 자동으로 presigned URL을 DB에 저장 | 구현이 작다 | URL 만료 후 재발급이 불가능하다 | 제외 |
| export를 처음부터 S3 multipart/manifest로 작성 | 대용량 resume 안정성이 좋다 | Phase 8.3보다 큰 hardening 범위다 | 후속 |
| archive script가 앱 API를 호출해 upload 위임 | 앱 port 재사용 가능 | archive job이 앱 runtime 가용성에 묶인다 | 제외 |
