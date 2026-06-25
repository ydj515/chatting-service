# Phase 8.4 Hot Room Shard 분산 + 10k Release Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** hot room 승격 시 방별 write/fanout shard count를 `HOT=16`, `VERY_HOT=64`로 확장하고, 10,000 msg/sec 60초 조건을 명시적인 release gate로 판정한다.

**Architecture:** `room_storage_configs`를 방별 shard source of truth로 사용한다. 메시지 수락 경로는 `messageId`로 `writeShard`를, `roomSeq` round-robin으로 `streamShard`/`fanoutShard`를 계산한다. `room-policy` 자동 upsert는 shard count를 줄이지 않고 늘리기만 하며, release gate wrapper는 load runner 결과와 Prometheus metric을 함께 판정한다.

**Tech Stack:** Kotlin, Spring Boot Cache, JdbcTemplate, Redis Streams, Micrometer/Prometheus, Node.js `node:test`.

---

## File Structure

- Modify `chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt`: room shard config cache TTL 추가.
- Modify `chat-persistence/src/main/kotlin/config/CacheConfig.kt`: `roomShardConfigs` cache 등록.
- Modify `chat-persistence/src/main/kotlin/config/ChatRoomPolicyProperties.kt`: `hotShardCount=16`, `veryHotShardCount=64` 설정 추가.
- Modify `chat-persistence/src/main/kotlin/service/RoomStorageConfigReader.kt`: `RoomShardConfig` DTO와 `shardConfig(roomId)` reader 추가.
- Modify `chat-persistence/src/main/kotlin/repository/RoomStorageConfigJdbcRepository.kt`: write/fanout shard count 동시 조회.
- Modify `chat-persistence/src/main/kotlin/service/RoomHeatClassifier.kt`: heat level별 shard count 포함.
- Modify `chat-persistence/src/main/kotlin/repository/RoomPolicyJdbcRepository.kt`: automatic policy upsert가 shard count를 monotonic expansion으로 반영.
- Modify `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`: 메시지 수락 시 동적 shard count 사용.
- Modify `chat-persistence/src/test/kotlin/repository/RoomStorageConfigJdbcRepositoryTest.kt`: shard config reader 테스트.
- Modify `chat-persistence/src/test/kotlin/service/RoomHeatClassifierTest.kt`: heat level별 shard count 테스트.
- Modify `chat-persistence/src/test/kotlin/repository/RoomPolicyJdbcRepositoryTest.kt`: shard count upsert SQL 테스트.
- Modify `chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt`: 동적 shard assignment 테스트.
- Modify `chat-persistence/src/test/kotlin/service/ChatServiceImplMembershipEventTest.kt`: `ChatServiceImpl` 생성자 변경 반영.
- Modify `chat-persistence/src/test/kotlin/service/ChatServiceImplCanonicalHistoryTest.kt`: `ChatServiceImpl` 생성자 변경 반영.
- Modify `chat-persistence/src/test/kotlin/service/ChatServiceImplCursorPaginationTest.kt`: `ChatServiceImpl` 생성자 변경 반영.
- Modify `chat-persistence/src/test/kotlin/service/HotRoomFanoutWorkerTest.kt`: multi stream shard owner acquisition 회귀 테스트.
- Create `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`: release gate 인자/판정 pure module.
- Create `scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`: pure module tests.
- Create `scripts/phase8-hot-room-release-gate.mjs`: 10k release gate wrapper.
- Modify `chat-runtime-config/src/main/resources/application-docker.yml`: cache/shard/env defaults 추가.
- Modify `.env.example`: Phase 8.4 env defaults 추가.
- Modify `docs/configuration.md`: shard count와 release gate env 문서화.
- Modify `docs/infrastructure.md`: Phase 8.4 hot room shard 분산 운영 설명 추가.
- Modify `docs/observability_metrics.md`: release gate 판정 metric 설명 추가.
- Modify `README.md`: 10k release gate 실행 명령 추가.

## Task 1: Room Shard Config Reader And Cache

**Files:**
- Modify: `chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/config/CacheConfig.kt`
- Modify: `chat-persistence/src/main/kotlin/service/RoomStorageConfigReader.kt`
- Modify: `chat-persistence/src/main/kotlin/repository/RoomStorageConfigJdbcRepository.kt`
- Test: `chat-persistence/src/test/kotlin/repository/RoomStorageConfigJdbcRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository tests**

Append these tests to `chat-persistence/src/test/kotlin/repository/RoomStorageConfigJdbcRepositoryTest.kt`:

```kotlin
    @Test
    fun `shardConfig는 current와 fanout shard count를 함께 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                org.mockito.ArgumentMatchers.any<org.springframework.jdbc.core.RowMapper<com.chat.persistence.service.RoomShardConfig>>(),
                eq(10L),
            )
        ).thenReturn(com.chat.persistence.service.RoomShardConfig(writeShardCount = 16, fanoutShardCount = 64))

        val config = repository.shardConfig(10L)

        assertEquals(16, config.writeShardCount)
        assertEquals(64, config.fanoutShardCount)
    }

    @Test
    fun `shardConfig는 config row가 없으면 1과 1로 fallback한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                org.mockito.ArgumentMatchers.any<org.springframework.jdbc.core.RowMapper<com.chat.persistence.service.RoomShardConfig>>(),
                eq(10L),
            )
        ).thenThrow(EmptyResultDataAccessException(1))

        val config = repository.shardConfig(10L)

        assertEquals(1, config.writeShardCount)
        assertEquals(1, config.fanoutShardCount)
    }

    @Test
    fun `shardConfig는 1보다 작은 값을 1로 보정한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(
            jdbcTemplate.queryForObject(
                anyString(),
                org.mockito.ArgumentMatchers.any<org.springframework.jdbc.core.RowMapper<com.chat.persistence.service.RoomShardConfig>>(),
                eq(10L),
            )
        ).thenReturn(com.chat.persistence.service.RoomShardConfig(writeShardCount = 0, fanoutShardCount = -5))

        val config = repository.shardConfig(10L)

        assertEquals(1, config.writeShardCount)
        assertEquals(1, config.fanoutShardCount)
    }
```

- [ ] **Step 2: Run the repository test and verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.RoomStorageConfigJdbcRepositoryTest --no-daemon
```

Expected: FAIL because `RoomShardConfig` and `shardConfig(roomId)` do not exist.

- [ ] **Step 3: Add room shard cache property**

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
)
```

- [ ] **Step 4: Register the room shard config cache**

Modify the `RedisCacheManager.builder(...)` chain in `chat-persistence/src/main/kotlin/config/CacheConfig.kt`:

```kotlin
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(configuration)
            .withCacheConfiguration("users", configuration.entryTtl(cacheProperties.usersTtl))
            .withCacheConfiguration("chatRooms", configuration.entryTtl(cacheProperties.chatRoomsTtl))
            .withCacheConfiguration("chatRoomMembers", configuration.entryTtl(cacheProperties.chatRoomMembersTtl))
            .withCacheConfiguration("messages", configuration.entryTtl(cacheProperties.messagesTtl))
            .withCacheConfiguration("roomAdmissionPolicies", configuration.entryTtl(cacheProperties.roomAdmissionPoliciesTtl))
            .withCacheConfiguration("roomShardConfigs", configuration.entryTtl(cacheProperties.roomShardConfigsTtl))
            .build()
```

- [ ] **Step 5: Add the RoomShardConfig reader contract**

Replace `chat-persistence/src/main/kotlin/service/RoomStorageConfigReader.kt` with:

```kotlin
package com.chat.persistence.service

data class RoomShardConfig(
    val writeShardCount: Int = 1,
    val fanoutShardCount: Int = 1,
)

interface RoomStorageConfigReader {
    fun currentShardCount(roomId: Long): Int

    fun shardConfig(roomId: Long): RoomShardConfig
}
```

- [ ] **Step 6: Implement the JdbcTemplate reader**

Modify `chat-persistence/src/main/kotlin/repository/RoomStorageConfigJdbcRepository.kt`:

```kotlin
import com.chat.persistence.service.RoomShardConfig
```

Add this method below `currentShardCount`:

```kotlin
    @Cacheable(value = ["roomShardConfigs"], key = "#roomId")
    override fun shardConfig(roomId: Long): RoomShardConfig {
        return try {
            val config = jdbcTemplate.queryForObject(
                SELECT_SHARD_CONFIG_SQL,
                { rs, _ ->
                    RoomShardConfig(
                        writeShardCount = rs.getInt("current_shard_count"),
                        fanoutShardCount = rs.getInt("fanout_shard_count"),
                    )
                },
                roomId,
            ) ?: RoomShardConfig()
            config.sanitized()
        } catch (e: EmptyResultDataAccessException) {
            RoomShardConfig()
        }
    }

    private fun RoomShardConfig.sanitized(): RoomShardConfig {
        return copy(
            writeShardCount = writeShardCount.coerceAtLeast(MIN_SHARD_COUNT),
            fanoutShardCount = fanoutShardCount.coerceAtLeast(MIN_SHARD_COUNT),
        )
    }
```

Add the SQL constant:

```kotlin
        const val SELECT_SHARD_CONFIG_SQL = """
            SELECT
                current_shard_count,
                fanout_shard_count
            FROM room_storage_configs
            WHERE room_id = ?
        """
```

- [ ] **Step 7: Run the repository test and verify it passes**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.RoomStorageConfigJdbcRepositoryTest --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit Task 1**

```bash
git add chat-persistence/src/main/kotlin/config/ChatCacheProperties.kt \
  chat-persistence/src/main/kotlin/config/CacheConfig.kt \
  chat-persistence/src/main/kotlin/service/RoomStorageConfigReader.kt \
  chat-persistence/src/main/kotlin/repository/RoomStorageConfigJdbcRepository.kt \
  chat-persistence/src/test/kotlin/repository/RoomStorageConfigJdbcRepositoryTest.kt
git commit -m "feat: add room shard config reader"
```

## Task 2: Heat Policy Shard Count Expansion

**Files:**
- Modify: `chat-persistence/src/main/kotlin/config/ChatRoomPolicyProperties.kt`
- Modify: `chat-persistence/src/main/kotlin/service/RoomHeatClassifier.kt`
- Modify: `chat-persistence/src/main/kotlin/repository/RoomPolicyJdbcRepository.kt`
- Test: `chat-persistence/src/test/kotlin/service/RoomHeatClassifierTest.kt`
- Test: `chat-persistence/src/test/kotlin/repository/RoomPolicyJdbcRepositoryTest.kt`

- [ ] **Step 1: Write the failing heat classifier tests**

Append these tests to `chat-persistence/src/test/kotlin/service/RoomHeatClassifierTest.kt`:

```kotlin
    @Test
    fun `HOT 방은 shard count를 16으로 확장한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 1000,
                roomMessagesP95PerSecond = 1000,
            ),
        )

        assertEquals(16, policy.writeShardCount)
        assertEquals(16, policy.fanoutShardCount)
    }

    @Test
    fun `VERY_HOT 방은 shard count를 64로 확장한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 5000,
                roomMessagesP95PerSecond = 3000,
            ),
        )

        assertEquals(64, policy.writeShardCount)
        assertEquals(64, policy.fanoutShardCount)
    }

    @Test
    fun `OVERLOAD 방은 VERY_HOT과 같은 shard count를 유지한다`() {
        val policy = classifier().classify(
            RoomTrafficSnapshot(
                roomId = 10L,
                roomMessagesPerSecond = 100,
                roomMessagesP95PerSecond = 100,
                fanoutLagMillis = 3001,
            ),
        )

        assertEquals(64, policy.writeShardCount)
        assertEquals(64, policy.fanoutShardCount)
    }
```

- [ ] **Step 2: Run the heat classifier test and verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.RoomHeatClassifierTest --no-daemon
```

Expected: FAIL because `RoomHeatPolicy.writeShardCount` and `fanoutShardCount` do not exist.

- [ ] **Step 3: Add shard count properties**

Modify `chat-persistence/src/main/kotlin/config/ChatRoomPolicyProperties.kt`:

```kotlin
    val hotShardCount: Int = 16,
    val veryHotShardCount: Int = 64,
```

Place these properties after `veryHotMessagesPerSecond`.

- [ ] **Step 4: Add shard count fields to RoomHeatPolicy**

Modify `RoomHeatPolicy` in `chat-persistence/src/main/kotlin/service/RoomHeatClassifier.kt`:

```kotlin
data class RoomHeatPolicy(
    val roomId: Long,
    val heatLevel: RoomHeatLevel,
    val liveFeedMaxMessages: Int,
    val liveFeedMaxAgeSeconds: Int,
    val roomRateLimitPerSecond: Int?,
    val slowModeSeconds: Int?,
    val writeShardCount: Int,
    val fanoutShardCount: Int,
)
```

- [ ] **Step 5: Return shard counts from classifier**

Modify the `classify` branches in `RoomHeatClassifier`:

```kotlin
            snapshot.isOverload() -> snapshot.policy(
                heatLevel = RoomHeatLevel.OVERLOAD,
                liveFeedMaxMessages = properties.overloadLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.overloadLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = properties.overloadRoomRateLimitPerSecond,
                slowModeSeconds = properties.overloadSlowModeSeconds,
                shardCount = properties.veryHotShardCount,
            )

            snapshot.isVeryHot() -> snapshot.policy(
                heatLevel = RoomHeatLevel.VERY_HOT,
                liveFeedMaxMessages = properties.veryHotLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.veryHotLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = properties.veryHotRoomRateLimitPerSecond,
                slowModeSeconds = properties.veryHotSlowModeSeconds,
                shardCount = properties.veryHotShardCount,
            )

            snapshot.roomMessagesPerSecond >= properties.hotMessagesPerSecond -> snapshot.policy(
                heatLevel = RoomHeatLevel.HOT,
                liveFeedMaxMessages = properties.normalLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.normalLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = null,
                slowModeSeconds = properties.hotSlowModeSeconds,
                shardCount = properties.hotShardCount,
            )

            else -> snapshot.policy(
                heatLevel = RoomHeatLevel.NORMAL,
                liveFeedMaxMessages = properties.normalLiveFeedMaxMessages,
                liveFeedMaxAgeSeconds = properties.normalLiveFeedMaxAgeSeconds,
                roomRateLimitPerSecond = null,
                slowModeSeconds = null,
                shardCount = MIN_SHARD_COUNT,
            )
```

Modify the private `policy` function:

```kotlin
    private fun RoomTrafficSnapshot.policy(
        heatLevel: RoomHeatLevel,
        liveFeedMaxMessages: Int,
        liveFeedMaxAgeSeconds: Int,
        roomRateLimitPerSecond: Int?,
        slowModeSeconds: Int?,
        shardCount: Int,
    ): RoomHeatPolicy {
        val sanitizedShardCount = shardCount.coerceAtLeast(MIN_SHARD_COUNT)
        return RoomHeatPolicy(
            roomId = roomId,
            heatLevel = heatLevel,
            liveFeedMaxMessages = liveFeedMaxMessages,
            liveFeedMaxAgeSeconds = liveFeedMaxAgeSeconds,
            roomRateLimitPerSecond = roomRateLimitPerSecond,
            slowModeSeconds = slowModeSeconds,
            writeShardCount = sanitizedShardCount,
            fanoutShardCount = sanitizedShardCount,
        )
    }

    private companion object {
        const val MIN_SHARD_COUNT = 1
    }
```

- [ ] **Step 6: Update repository test for shard upsert**

Modify `RoomPolicyJdbcRepositoryTest` policy creation:

```kotlin
            RoomHeatPolicy(
                roomId = 10L,
                heatLevel = RoomHeatLevel.VERY_HOT,
                liveFeedMaxMessages = 500,
                liveFeedMaxAgeSeconds = 30,
                roomRateLimitPerSecond = 5000,
                slowModeSeconds = 1,
                writeShardCount = 64,
                fanoutShardCount = 64,
            ),
```

Modify the `verify(jdbcTemplate).update(...)` expectation:

```kotlin
        verify(jdbcTemplate).update(
            sqlCaptor.capture(),
            eq(10L),
            eq("VERY_HOT"),
            eq(500),
            eq(30),
            eq(5000),
            eq(1),
            eq(64),
            eq(64),
        )
```

Add these assertions:

```kotlin
        assertTrue(sqlCaptor.value.contains("current_shard_count"))
        assertTrue(sqlCaptor.value.contains("fanout_shard_count"))
        assertTrue(sqlCaptor.value.contains("GREATEST(room_storage_configs.current_shard_count, EXCLUDED.current_shard_count)"))
        assertTrue(sqlCaptor.value.contains("GREATEST(room_storage_configs.fanout_shard_count, EXCLUDED.fanout_shard_count)"))
```

- [ ] **Step 7: Run the policy repository test and verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.repository.RoomPolicyJdbcRepositoryTest --no-daemon
```

Expected: FAIL because the SQL and update arguments do not include shard count columns.

- [ ] **Step 8: Add shard count columns to automatic policy upsert**

Modify `RoomPolicyJdbcRepository.applyAutomaticPolicy(...)`:

```kotlin
        jdbcTemplate.update(
            UPSERT_AUTOMATIC_POLICY_SQL,
            policy.roomId,
            policy.heatLevel.name,
            policy.liveFeedMaxMessages,
            policy.liveFeedMaxAgeSeconds,
            policy.roomRateLimitPerSecond,
            policy.slowModeSeconds,
            policy.writeShardCount,
            policy.fanoutShardCount,
        )
```

Modify `UPSERT_AUTOMATIC_POLICY_SQL`:

```sql
            INSERT INTO room_storage_configs (
                room_id,
                hot_room_policy,
                live_feed_max_messages,
                live_feed_max_age_seconds,
                room_rate_limit_per_second,
                slow_mode_seconds,
                current_shard_count,
                fanout_shard_count,
                auto_policy_enabled,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, now())
            ON CONFLICT (room_id) DO UPDATE SET
                hot_room_policy = EXCLUDED.hot_room_policy,
                live_feed_max_messages = EXCLUDED.live_feed_max_messages,
                live_feed_max_age_seconds = EXCLUDED.live_feed_max_age_seconds,
                room_rate_limit_per_second = EXCLUDED.room_rate_limit_per_second,
                slow_mode_seconds = EXCLUDED.slow_mode_seconds,
                current_shard_count = GREATEST(room_storage_configs.current_shard_count, EXCLUDED.current_shard_count),
                fanout_shard_count = GREATEST(room_storage_configs.fanout_shard_count, EXCLUDED.fanout_shard_count),
                updated_at = now()
            WHERE room_storage_configs.auto_policy_enabled = true
```

- [ ] **Step 9: Run Task 2 tests and verify they pass**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.RoomHeatClassifierTest --tests com.chat.persistence.repository.RoomPolicyJdbcRepositoryTest --no-daemon
```

Expected: PASS.

- [ ] **Step 10: Commit Task 2**

```bash
git add chat-persistence/src/main/kotlin/config/ChatRoomPolicyProperties.kt \
  chat-persistence/src/main/kotlin/service/RoomHeatClassifier.kt \
  chat-persistence/src/main/kotlin/repository/RoomPolicyJdbcRepository.kt \
  chat-persistence/src/test/kotlin/service/RoomHeatClassifierTest.kt \
  chat-persistence/src/test/kotlin/repository/RoomPolicyJdbcRepositoryTest.kt
git commit -m "feat: expand hot room shard policy"
```

## Task 3: Dynamic Message Shard Assignment

**Files:**
- Modify: `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`
- Test: `chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/ChatServiceImplMembershipEventTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/ChatServiceImplCanonicalHistoryTest.kt`
- Test: `chat-persistence/src/test/kotlin/service/ChatServiceImplCursorPaginationTest.kt`

- [ ] **Step 1: Write the failing shard assignment test**

Add this import to `ChatServiceImplMessageContractTest`:

```kotlin
import org.mockito.Mockito.times
```

Append this test:

```kotlin
    @Test
    fun `메시지 전송은 방별 shard config로 stream과 fanout shard를 roomSeq round robin으로 계산한다`() {
        val messageStreamProducer = mock(MessageStreamProducer::class.java)
        `when`(messageStreamProducer.append(anyMessageStreamEnvelope()))
            .thenReturn("1749790000000-0")
            .thenReturn("1749790000001-0")
        val shardReader = FixedRoomStorageConfigReader(RoomShardConfig(writeShardCount = 4, fanoutShardCount = 16))
        val fixture = chatServiceFixture(
            messageStreamProducer = messageStreamProducer,
            roomStorageConfigReader = shardReader,
            sequenceValues = listOf(1L, 2L),
        )
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                "client-message-1",
            )
        ).thenReturn(Optional.empty())
        `when`(
            fixture.messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                10L,
                7L,
                "client-message-2",
            )
        ).thenReturn(Optional.empty())

        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello-1",
                clientMessageId = "client-message-1",
            ),
            senderId = 7L,
        )
        fixture.chatService.sendMessage(
            SendMessageRequest(
                chatRoomId = 10L,
                type = MessageType.TEXT,
                content = "hello-2",
                clientMessageId = "client-message-2",
            ),
            senderId = 7L,
        )

        val envelopeCaptor = ArgumentCaptor.forClass(MessageStreamEnvelope::class.java)
        verify(messageStreamProducer, times(2)).append(captureMessageStreamEnvelope(envelopeCaptor))
        val envelopes = envelopeCaptor.allValues
        assertEquals(listOf(0, 1), envelopes.map { it.streamShard })
        assertEquals(listOf(0, 1), envelopes.map { it.fanoutShard })
        envelopes.forEach { envelope ->
            assertTrue(envelope.writeShard in 0..3)
        }
        assertEquals(listOf(10L, 10L), shardReader.requestedRoomIds)
    }
```

Add this fake reader near the existing fake services:

```kotlin
    private class FixedRoomStorageConfigReader(
        private val config: RoomShardConfig,
    ) : RoomStorageConfigReader {
        val requestedRoomIds = mutableListOf<Long>()

        override fun currentShardCount(roomId: Long): Int {
            requestedRoomIds += roomId
            return config.writeShardCount
        }

        override fun shardConfig(roomId: Long): RoomShardConfig {
            requestedRoomIds += roomId
            return config
        }
    }
```

- [ ] **Step 2: Update the fixture signature for the test**

Modify `chatServiceFixture(...)` parameters:

```kotlin
        roomStorageConfigReader: RoomStorageConfigReader = FixedRoomStorageConfigReader(RoomShardConfig()),
        sequenceValues: List<Long> = listOf(1L),
```

Modify sequence stubbing:

```kotlin
        val sequenceStubbing = `when`(valueOperations.increment("chat:sequence:10", 1L))
        sequenceValues.forEach { sequenceValue ->
            sequenceStubbing.thenReturn(sequenceValue)
        }
```

Pass `roomStorageConfigReader` into `ChatServiceImpl(...)`:

```kotlin
                roomTrafficStatsService = roomTrafficStatsService,
                roomStorageConfigReader = roomStorageConfigReader,
```

- [ ] **Step 3: Run the message contract test and verify it fails**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.ChatServiceImplMessageContractTest --no-daemon
```

Expected: FAIL because `ChatServiceImpl` does not accept `RoomStorageConfigReader` and still uses `SHARD_COUNT = 1`.

- [ ] **Step 4: Inject RoomStorageConfigReader into ChatServiceImpl**

Modify the constructor in `chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt`:

```kotlin
    private val messageAdmissionPolicyService: MessageAdmissionPolicyService,
    private val roomTrafficStatsService: RoomTrafficStatsService,
    private val roomStorageConfigReader: RoomStorageConfigReader,
) : ChatService {
```

- [ ] **Step 5: Use room shard config during sendMessage**

In `sendMessage`, after `roomSeq` is assigned, add:

```kotlin
        val shardConfig = roomStorageConfigReader.shardConfig(request.chatRoomId)
        val streamShard = streamShard(roomSeq, shardConfig.fanoutShardCount)
```

Modify the `Message(...)` construction shard fields:

```kotlin
            streamShard = streamShard,
            writeShard = writeShard(messageId, shardConfig.writeShardCount),
            fanoutShard = fanoutShard(streamShard),
```

- [ ] **Step 6: Replace fixed shard helpers**

Replace the shard helper functions at the bottom of `ChatServiceImpl`:

```kotlin
    private fun streamShard(roomSeq: Long, shardCount: Int): Int {
        return Math.floorMod(roomSeq - 1, shardCount.coerceAtLeast(1).toLong()).toInt()
    }

    private fun writeShard(messageId: String, shardCount: Int): Int = shard(messageId, shardCount)

    private fun fanoutShard(streamShard: Int): Int = streamShard

    private fun shard(value: String, shardCount: Int): Int {
        return Math.floorMod(value.hashCode(), shardCount.coerceAtLeast(1))
    }
```

Remove the `private companion object { const val SHARD_COUNT = 1 }` block.

- [ ] **Step 7: Run the message contract test and verify it passes**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.ChatServiceImplMessageContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Update other ChatServiceImpl test fixtures**

Add these imports to each file that directly constructs `ChatServiceImpl`:

- `chat-persistence/src/test/kotlin/service/ChatServiceImplMembershipEventTest.kt`
- `chat-persistence/src/test/kotlin/service/ChatServiceImplCanonicalHistoryTest.kt`
- `chat-persistence/src/test/kotlin/service/ChatServiceImplCursorPaginationTest.kt`

```kotlin
import com.chat.persistence.service.RoomShardConfig
import com.chat.persistence.service.RoomStorageConfigReader
```

In each `ChatServiceImpl(...)` construction, add:

```kotlin
            roomStorageConfigReader = TestRoomStorageConfigReader,
```

Add this private object to each test class:

```kotlin
    private object TestRoomStorageConfigReader : RoomStorageConfigReader {
        override fun currentShardCount(roomId: Long): Int = 1

        override fun shardConfig(roomId: Long): RoomShardConfig = RoomShardConfig()
    }
```

- [ ] **Step 9: Run all ChatServiceImpl direct-construction tests**

Run:

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.service.ChatServiceImplMessageContractTest \
  --tests com.chat.persistence.service.ChatServiceImplMembershipEventTest \
  --tests com.chat.persistence.service.ChatServiceImplCanonicalHistoryTest \
  --tests com.chat.persistence.service.ChatServiceImplCursorPaginationTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 10: Commit Task 3**

```bash
git add chat-persistence/src/main/kotlin/service/ChatServiceImpl.kt \
  chat-persistence/src/test/kotlin/service/ChatServiceImplMessageContractTest.kt \
  chat-persistence/src/test/kotlin/service/ChatServiceImplMembershipEventTest.kt \
  chat-persistence/src/test/kotlin/service/ChatServiceImplCanonicalHistoryTest.kt \
  chat-persistence/src/test/kotlin/service/ChatServiceImplCursorPaginationTest.kt
git commit -m "feat: assign hot room messages across shards"
```

## Task 4: Multi-Shard Fanout Owner Regression

**Files:**
- Modify: `chat-persistence/src/test/kotlin/service/HotRoomFanoutWorkerTest.kt`

- [ ] **Step 1: Write the multi-shard fanout test**

Append this test to `HotRoomFanoutWorkerTest`:

```kotlin
    @Test
    fun `fanout worker는 여러 stream shard에 대해 owner lease를 각각 획득하고 batch를 publish한다`() {
        val consumer = FakeMessageStreamConsumer(
            records = listOf(
                streamRecord(recordId = "1749790000000-0", roomSeq = 1L, streamShard = 0),
                streamRecord(recordId = "1749790000001-0", roomSeq = 2L, streamShard = 1),
            ),
        )
        val redisMessageBroker = mock(RedisMessageBroker::class.java)
        val leaseService = PerShardFanoutOwnerLeaseService()
        val worker = HotRoomFanoutWorker(
            messageStreamConsumer = consumer,
            redisMessageBroker = redisMessageBroker,
            workerProperties = ChatWorkerProperties(
                consumerName = "worker-1",
                fanout = ChatWorkerProperties.StreamConsumer(
                    consumerGroup = "fanout",
                    readCount = 10,
                ),
            ),
            fanoutOwnerLeaseService = leaseService,
        )

        val broadcastCount = worker.pollAndFanout()

        assertEquals(2, broadcastCount)
        assertEquals(listOf("10:0", "10:1"), leaseService.acquireAttempts)
        assertEquals(
            listOf(
                "chat:stream:room:10:shard:0:fanout",
                "chat:stream:room:10:shard:1:fanout",
            ),
            consumer.ensuredGroups,
        )
        assertEquals(
            listOf(
                "chat:stream:room:10:shard:0:fanout:1749790000000-0",
                "chat:stream:room:10:shard:1:fanout:1749790000001-0",
            ),
            consumer.acked,
        )
    }
```

- [ ] **Step 2: Update the streamRecord helper**

Replace the helper signature and stream key usage:

```kotlin
    private fun streamRecord(
        recordId: String,
        roomSeq: Long,
        streamShard: Int = 0,
    ): MessageStreamRecord {
        return MessageStreamRecord(
            streamKey = "chat:stream:room:10:shard:$streamShard",
            recordId = recordId,
            envelope = MessageStreamEnvelope(
                messageId = "msg-$roomSeq",
                clientMessageId = "client-$roomSeq",
                chatRoomId = 10L,
                senderId = 7L,
                senderName = "User 7",
                messageType = MessageType.TEXT,
                content = "hello-$roomSeq",
                sequenceNumber = roomSeq,
                roomSeq = roomSeq,
                streamShard = streamShard,
                writeShard = 1,
                fanoutShard = streamShard,
                createdAt = LocalDateTime.parse("2026-06-13T12:00:00"),
            ),
        )
    }
```

- [ ] **Step 3: Update FakeMessageStreamConsumer for multiple streams**

Modify `listStreamKeys`, `readNew`, and `claimPending`:

```kotlin
        override fun listStreamKeys(): Set<String> {
            return (records + claimedRecords).mapTo(sortedSetOf()) { it.streamKey }
        }

        override fun readNew(
            consumerGroup: String,
            consumerName: String,
            streamKeys: Set<String>,
            count: Long,
        ): List<MessageStreamRecord> {
            reads += "${streamKeys.sorted().joinToString(",")}:$consumerGroup:$consumerName"
            return records.filter { it.streamKey in streamKeys }
        }

        override fun claimPending(
            consumerGroup: String,
            consumerName: String,
            streamKeys: Set<String>,
            count: Long,
            minIdleMillis: Long,
        ): List<MessageStreamRecord> {
            claims += "${streamKeys.sorted().joinToString(",")}:$consumerGroup:$consumerName:$minIdleMillis"
            return claimedRecords.filter { it.streamKey in streamKeys }
        }
```

Update existing assertions that previously expected only the first stream in `reads` or `claims` to use the single stream key string. Single stream expectations remain identical because `joinToString(",")` returns the same single key.

- [ ] **Step 4: Add per-shard lease fake**

Add this class near `FakeFanoutOwnerLeaseService`:

```kotlin
    private class PerShardFanoutOwnerLeaseService : FanoutOwnerLeaseService {
        val acquireAttempts = mutableListOf<String>()

        override fun acquire(roomId: Long, streamShard: Int): FanoutOwnerLease? {
            acquireAttempts += "$roomId:$streamShard"
            return FanoutOwnerLease(
                key = "chat:fanout:owner:room:$roomId:shard:$streamShard",
                value = "worker-1:token-$streamShard",
                roomId = roomId,
                streamShard = streamShard,
            )
        }

        override fun validate(
            lease: FanoutOwnerLease,
            stage: FanoutOwnerLeaseValidationStage,
        ): Boolean = true

        override fun release(lease: FanoutOwnerLease) = Unit
    }
```

- [ ] **Step 5: Run the fanout worker test**

Run:

```bash
./gradlew :chat-persistence:test --tests com.chat.persistence.service.HotRoomFanoutWorkerTest --no-daemon
```

Expected: PASS. This task is a regression guard for existing fanout owner behavior after Task 3 starts creating multiple stream shards.

- [ ] **Step 6: Commit Task 4**

```bash
git add chat-persistence/src/test/kotlin/service/HotRoomFanoutWorkerTest.kt
git commit -m "test: cover multi shard fanout owner distribution"
```

## Task 5: Phase 8.4 Release Gate Wrapper

**Files:**
- Create: `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`
- Create: `scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`
- Create: `scripts/phase8-hot-room-release-gate.mjs`

- [ ] **Step 1: Write the failing release gate pure tests**

Create `scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs`:

```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildLoadChatArgs,
  parsePhase8HotRoomGateArgs,
  prometheusQueries,
} from './phase8HotRoomReleaseGatePlan.mjs';

test('parsePhase8HotRoomGateArgs defaults to 10k messages per second for 60 seconds', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.equal(options.room, 'hot');
  assert.equal(options.viewers, 10000);
  assert.equal(options.messagesPerSec, 10000);
  assert.equal(options.durationSeconds, 60);
  assert.equal(options.minReceivedRatio, 0.9);
  assert.equal(options.minStreamShardCount, 16);
  assert.equal(options.maxFanoutP95Ms, 500);
  assert.equal(options.maxStreamGroupLagEntries, 1000);
});

test('buildLoadChatArgs includes release gate load arguments', () => {
  const args = buildLoadChatArgs(parsePhase8HotRoomGateArgs(['--room', 'arena']));

  assert.deepEqual(args, [
    'scripts/load-chat.mjs',
    '--room', 'arena',
    '--viewers', '10000',
    '--messages-per-sec', '10000',
    '--duration', '60',
    '--min-received-ratio', '0.9',
    '--assert-room-seq-order',
  ]);
});

test('assertLoadSummary accepts a successful 10k summary', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assertLoadSummary({
    ok: true,
    sent: 600000,
    viewers: 10000,
    receivedPerViewer: [600000, 590000],
    minReceivedRatio: 0.9,
    assertedRoomSeqOrder: true,
  }, options);
});

test('assertLoadSummary rejects insufficient delivery', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.throws(() => assertLoadSummary({
    ok: true,
    sent: 600000,
    viewers: 10000,
    receivedPerViewer: [500000],
    minReceivedRatio: 0.9,
    assertedRoomSeqOrder: true,
  }, options), /minimum received/);
});

test('assertPrometheusSnapshot accepts fanout latency and shard distribution within thresholds', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assertPrometheusSnapshot({
    fanoutP95Seconds: 0.42,
    observedStreamShardCount: 16,
    maxStreamGroupLagEntries: 1000,
  }, options);
});

test('assertPrometheusSnapshot rejects too few observed stream shards', () => {
  const options = parsePhase8HotRoomGateArgs([]);

  assert.throws(() => assertPrometheusSnapshot({
    fanoutP95Seconds: 0.42,
    observedStreamShardCount: 1,
    maxStreamGroupLagEntries: 1000,
  }, options), /stream shard/);
});

test('prometheusQueries use bounded stream shard and fanout metrics', () => {
  assert.match(prometheusQueries.fanoutP95Seconds, /chat_redis_stream_worker_batch_latency_seconds_bucket/);
  assert.match(prometheusQueries.observedStreamShardCount, /stream_shard/);
  assert.match(prometheusQueries.maxStreamGroupLagEntries, /chat_redis_stream_group_lag/);
});
```

- [ ] **Step 2: Run the pure test and verify it fails**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: FAIL because `scripts/lib/phase8HotRoomReleaseGatePlan.mjs` does not exist.

- [ ] **Step 3: Implement the pure release gate module**

Create `scripts/lib/phase8HotRoomReleaseGatePlan.mjs`:

```javascript
export const prometheusQueries = {
  fanoutP95Seconds:
    'histogram_quantile(0.95, sum(rate(chat_redis_stream_worker_batch_latency_seconds_bucket{worker_role="fanout",outcome="success"}[1m])) by (le))',
  observedStreamShardCount:
    'count(count by (stream_shard) (chat_redis_stream_group_lag{consumer_group="fanout",stream_shard!="unknown"}))',
  maxStreamGroupLagEntries:
    'max(chat_redis_stream_group_lag{stream_shard!="unknown"})',
};

export function parsePhase8HotRoomGateArgs(argv, env = process.env) {
  const options = {
    room: 'hot',
    viewers: 10000,
    messagesPerSec: 10000,
    durationSeconds: 60,
    minReceivedRatio: 0.9,
    minStreamShardCount: 16,
    maxFanoutP95Ms: 500,
    maxStreamGroupLagEntries: 1000,
    prometheusUrl: env.CHAT_PROMETHEUS_URL ?? 'http://localhost:9090',
    loadScript: 'scripts/load-chat.mjs',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--room') {
      options.room = value;
    } else if (arg === '--viewers') {
      options.viewers = positiveInteger(value, arg);
    } else if (arg === '--messages-per-sec') {
      options.messagesPerSec = positiveInteger(value, arg);
    } else if (arg === '--duration') {
      options.durationSeconds = positiveInteger(value, arg);
    } else if (arg === '--min-received-ratio') {
      options.minReceivedRatio = ratio(value, arg);
    } else if (arg === '--min-stream-shards') {
      options.minStreamShardCount = positiveInteger(value, arg);
    } else if (arg === '--max-fanout-p95-ms') {
      options.maxFanoutP95Ms = positiveInteger(value, arg);
    } else if (arg === '--max-stream-lag') {
      options.maxStreamGroupLagEntries = positiveInteger(value, arg);
    } else if (arg === '--prometheus-url') {
      options.prometheusUrl = value;
    } else if (arg === '--load-script') {
      options.loadScript = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return options;
}

export function buildLoadChatArgs(options) {
  return [
    options.loadScript,
    '--room', options.room,
    '--viewers', String(options.viewers),
    '--messages-per-sec', String(options.messagesPerSec),
    '--duration', String(options.durationSeconds),
    '--min-received-ratio', String(options.minReceivedRatio),
    '--assert-room-seq-order',
  ];
}

export function assertLoadSummary(summary, options) {
  if (summary?.ok !== true) {
    throw new Error('load summary did not report ok=true');
  }
  const expectedSent = options.messagesPerSec * options.durationSeconds;
  if (summary.sent < expectedSent) {
    throw new Error(`load summary sent ${summary.sent}; expected at least ${expectedSent}`);
  }
  if (summary.viewers !== options.viewers) {
    throw new Error(`load summary viewers ${summary.viewers}; expected ${options.viewers}`);
  }
  if (summary.assertedRoomSeqOrder !== true) {
    throw new Error('load summary did not assert roomSeq order');
  }
  const minimumReceived = Math.ceil(summary.sent * options.minReceivedRatio);
  for (const [index, received] of (summary.receivedPerViewer ?? []).entries()) {
    if (received < minimumReceived) {
      throw new Error(`viewer ${index} received ${received}; minimum received is ${minimumReceived}`);
    }
  }
}

export function assertPrometheusSnapshot(snapshot, options) {
  const maxFanoutP95Seconds = options.maxFanoutP95Ms / 1000;
  if (snapshot.fanoutP95Seconds > maxFanoutP95Seconds) {
    throw new Error(`fanout p95 ${snapshot.fanoutP95Seconds}s exceeded ${maxFanoutP95Seconds}s`);
  }
  if (snapshot.observedStreamShardCount < options.minStreamShardCount) {
    throw new Error(
      `observed stream shard count ${snapshot.observedStreamShardCount}; expected at least ${options.minStreamShardCount}`,
    );
  }
  if (snapshot.maxStreamGroupLagEntries > options.maxStreamGroupLagEntries) {
    throw new Error(
      `stream group lag ${snapshot.maxStreamGroupLagEntries}; expected at most ${options.maxStreamGroupLagEntries}`,
    );
  }
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function ratio(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    throw new Error(`${name} must be a number between 0 and 1`);
  }
  return parsed;
}
```

- [ ] **Step 4: Run the pure release gate test**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Create the executable wrapper**

Create `scripts/phase8-hot-room-release-gate.mjs`:

```javascript
#!/usr/bin/env node
import { spawn } from 'node:child_process';
import {
  assertLoadSummary,
  assertPrometheusSnapshot,
  buildLoadChatArgs,
  parsePhase8HotRoomGateArgs,
  prometheusQueries,
} from './lib/phase8HotRoomReleaseGatePlan.mjs';

async function main() {
  const options = parsePhase8HotRoomGateArgs(process.argv.slice(2));
  const loadSummary = await runLoadChat(options);
  assertLoadSummary(loadSummary, options);
  const prometheusSnapshot = await readPrometheusSnapshot(options.prometheusUrl);
  assertPrometheusSnapshot(prometheusSnapshot, options);
  console.log(JSON.stringify({
    ok: true,
    loadSummary,
    prometheusSnapshot,
    thresholds: {
      minStreamShardCount: options.minStreamShardCount,
      maxFanoutP95Ms: options.maxFanoutP95Ms,
      maxStreamGroupLagEntries: options.maxStreamGroupLagEntries,
    },
  }, null, 2));
}

async function runLoadChat(options) {
  const args = buildLoadChatArgs(options);
  const output = await runProcess(process.execPath, args);
  return JSON.parse(output);
}

function runProcess(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`${command} ${args.join(' ')} failed with code ${code}: ${stderr.trim()}`));
        return;
      }
      resolve(stdout.trim());
    });
  });
}

async function readPrometheusSnapshot(prometheusUrl) {
  return {
    fanoutP95Seconds: await queryPrometheusNumber(prometheusUrl, prometheusQueries.fanoutP95Seconds),
    observedStreamShardCount: await queryPrometheusNumber(prometheusUrl, prometheusQueries.observedStreamShardCount),
    maxStreamGroupLagEntries: await queryPrometheusNumber(prometheusUrl, prometheusQueries.maxStreamGroupLagEntries),
  };
}

async function queryPrometheusNumber(prometheusUrl, query) {
  const url = new URL('/api/v1/query', prometheusUrl);
  url.searchParams.set('query', query);
  const response = await fetch(url);
  const body = await response.json();
  if (!response.ok || body.status !== 'success') {
    throw new Error(`Prometheus query failed: ${query}`);
  }
  const result = body.data?.result?.[0]?.value?.[1];
  const value = Number(result ?? 0);
  if (!Number.isFinite(value)) {
    throw new Error(`Prometheus query returned non-numeric value: ${query}`);
  }
  return value;
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
```

- [ ] **Step 6: Run syntax and pure tests**

Run:

```bash
node --check scripts/phase8-hot-room-release-gate.mjs
node --check scripts/lib/phase8HotRoomReleaseGatePlan.mjs
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
```

Expected: all commands PASS.

- [ ] **Step 7: Commit Task 5**

```bash
git add scripts/phase8-hot-room-release-gate.mjs \
  scripts/lib/phase8HotRoomReleaseGatePlan.mjs \
  scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
git commit -m "feat: add phase8 hot room release gate"
```

## Task 6: Configuration And Operations Docs

**Files:**
- Modify: `chat-runtime-config/src/main/resources/application-docker.yml`
- Modify: `.env.example`
- Modify: `docs/configuration.md`
- Modify: `docs/infrastructure.md`
- Modify: `docs/observability_metrics.md`
- Modify: `README.md`

- [ ] **Step 1: Add application-docker defaults**

Modify `chat-runtime-config/src/main/resources/application-docker.yml`.

Under `chat.room-policy`, add:

```yaml
    hot-shard-count: ${CHAT_ROOM_POLICY_HOT_SHARD_COUNT:16}
    very-hot-shard-count: ${CHAT_ROOM_POLICY_VERY_HOT_SHARD_COUNT:64}
```

Under `chat.cache`, add:

```yaml
    room-shard-configs-ttl: ${CHAT_CACHE_ROOM_SHARD_CONFIGS_TTL:10s}
```

- [ ] **Step 2: Add `.env.example` defaults**

Add these lines to `.env.example` near the room policy variables:

```dotenv
CHAT_ROOM_POLICY_HOT_SHARD_COUNT=16
CHAT_ROOM_POLICY_VERY_HOT_SHARD_COUNT=64
CHAT_CACHE_ROOM_SHARD_CONFIGS_TTL=10s
CHAT_PROMETHEUS_URL=http://localhost:9090
```

- [ ] **Step 3: Document configuration**

Add these rows to `docs/configuration.md`:

```markdown
| `CHAT_ROOM_POLICY_HOT_SHARD_COUNT` | `16` | `HOT` 방으로 자동 승격될 때 적용하는 `current_shard_count`와 `fanout_shard_count` 목표값. 자동 정책은 기존 값보다 작게 줄이지 않는다 |
| `CHAT_ROOM_POLICY_VERY_HOT_SHARD_COUNT` | `64` | `VERY_HOT` 및 `OVERLOAD` 방에 적용하는 shard count 목표값. 단일 fanout owner 병목을 피하기 위한 Phase 8.4 기본값 |
| `CHAT_CACHE_ROOM_SHARD_CONFIGS_TTL` | `10s` | 메시지 수락 경로가 읽는 `room_storage_configs.current_shard_count/fanout_shard_count` cache TTL |
| `CHAT_PROMETHEUS_URL` | `http://localhost:9090` | `scripts/phase8-hot-room-release-gate.mjs`가 release gate metric을 조회하는 Prometheus base URL |
```

Add this paragraph below the room policy notes:

```markdown
Phase 8.4부터 `room-policy` worker는 heat/live feed/rate/slow-mode 정책과 함께 shard count를 자동 확장한다. `HOT`은 `16`, `VERY_HOT`과 `OVERLOAD`는 `64`를 기본값으로 사용한다. 자동 정책은 `GREATEST(existing, incoming)` 방식으로 shard count를 늘리기만 하며, downgrade 시 shard count를 줄이지 않는다.
```

- [ ] **Step 4: Document infrastructure behavior**

Add this section to `docs/infrastructure.md`:

```markdown
### Phase 8.4 Hot Room Shard 분산

Phase 8.4부터 메시지 수락 경로는 `room_storage_configs`의 `current_shard_count`와 `fanout_shard_count`를 읽어 새 메시지의 `writeShard`, `streamShard`, `fanoutShard`를 계산한다. `writeShard`는 `messageId` hash 기반이고, `streamShard`와 `fanoutShard`는 `roomSeq` round-robin 기반이다.

`room-policy` worker는 `HOT=16`, `VERY_HOT=64`를 기본 shard count로 upsert한다. 자동 downgrade는 live feed window와 rate/slow-mode 정책은 갱신하지만 shard count를 줄이지 않는다. 이미 확장된 hot room이 다시 트래픽을 받을 때 단일 stream shard로 되돌아가 fanout owner 병목을 만들지 않기 위함이다.

10k release gate는 다음 명령으로 실행한다.

```bash
node scripts/phase8-hot-room-release-gate.mjs
```

이 명령은 `scripts/load-chat.mjs`로 10,000 msg/sec 60초 부하를 생성하고, Prometheus에서 fanout p95, stream shard 관측 수, Redis Streams group lag를 조회해 threshold를 넘으면 실패한다.
```

- [ ] **Step 5: Document observability gate metrics**

Add this table to `docs/observability_metrics.md` release gate section:

```markdown
| Phase 8.4 hot room shard 분산 | `count(count by (stream_shard) (chat_redis_stream_group_lag{consumer_group="fanout",stream_shard!="unknown"}))` | 16 이상 | HOT room release gate에서 fanout stream shard가 실제로 여러 개 관측되는지 확인 |
| Phase 8.4 fanout p95 | `histogram_quantile(0.95, sum(rate(chat_redis_stream_worker_batch_latency_seconds_bucket{worker_role="fanout",outcome="success"}[1m])) by (le))` | 0.5초 이하 | hot room batch fanout 처리 latency |
| Phase 8.4 stream lag | `max(chat_redis_stream_group_lag{stream_shard!="unknown"})` | 1000 entries 이하 | release gate 종료 시 backlog가 critical 수준으로 남지 않았는지 확인 |
```

- [ ] **Step 6: Document README command**

Add this command to the README verification section:

```bash
CHAT_PROMETHEUS_URL=http://localhost:9090 node scripts/phase8-hot-room-release-gate.mjs
```

- [ ] **Step 7: Run documentation checks**

Run:

```bash
rg -n "CHAT_ROOM_POLICY_HOT_SHARD_COUNT|phase8-hot-room-release-gate|roomShardConfigs|room-shard-configs" .env.example docs chat-runtime-config/src/main/resources/application-docker.yml
```

Expected: all new configuration and command references are present.

- [ ] **Step 8: Commit Task 6**

```bash
git add chat-runtime-config/src/main/resources/application-docker.yml \
  .env.example \
  docs/configuration.md \
  docs/infrastructure.md \
  docs/observability_metrics.md \
  README.md
git commit -m "docs: document phase8 hot room shard gate"
```

## Task 7: Final Verification

**Files:**
- Verify all modified files.

- [ ] **Step 1: Run focused Kotlin tests**

Run:

```bash
./gradlew :chat-persistence:test \
  --tests com.chat.persistence.repository.RoomStorageConfigJdbcRepositoryTest \
  --tests com.chat.persistence.service.RoomHeatClassifierTest \
  --tests com.chat.persistence.repository.RoomPolicyJdbcRepositoryTest \
  --tests com.chat.persistence.service.ChatServiceImplMessageContractTest \
  --tests com.chat.persistence.service.ChatServiceImplMembershipEventTest \
  --tests com.chat.persistence.service.ChatServiceImplCanonicalHistoryTest \
  --tests com.chat.persistence.service.ChatServiceImplCursorPaginationTest \
  --tests com.chat.persistence.service.HotRoomFanoutWorkerTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run Node tests and syntax checks**

Run:

```bash
node --test scripts/lib/phase8HotRoomReleaseGatePlan.test.mjs
node --check scripts/lib/phase8HotRoomReleaseGatePlan.mjs
node --check scripts/phase8-hot-room-release-gate.mjs
```

Expected: PASS.

- [ ] **Step 3: Run broader backend test suite**

Run:

```bash
./gradlew test --no-daemon
```

Expected: PASS.

- [ ] **Step 4: Run release gate command in a Compose environment**

Start the backend, workers, Prometheus, and Grafana:

```bash
docker compose up -d
```

Run the release gate:

```bash
CHAT_PROMETHEUS_URL=http://localhost:9090 node scripts/phase8-hot-room-release-gate.mjs
```

Expected: PASS with JSON containing `"ok": true`, `observedStreamShardCount >= 16`, `fanoutP95Seconds <= 0.5`, and `maxStreamGroupLagEntries <= 1000`.

- [ ] **Step 5: Inspect git status**

Run:

```bash
git status --short
```

Expected: clean working tree after commits.

## Self-Review

- Spec coverage: Task 1 covers shard config source of truth and cache. Task 2 covers `HOT=16`, `VERY_HOT=64`, `OVERLOAD=64`, and monotonic automatic expansion. Task 3 covers dynamic `writeShard`, `streamShard`, `fanoutShard` assignment. Task 4 covers multi-shard owner acquisition. Task 5 covers 10k release gate wrapper and Prometheus metric checks. Task 6 covers configuration and operations documentation.
- Placeholder scan: no placeholder sections remain. All new files have concrete content and all commands include expected results.
- Type consistency: `RoomShardConfig.writeShardCount/fanoutShardCount`, `RoomStorageConfigReader.shardConfig`, `RoomHeatPolicy.writeShardCount/fanoutShardCount`, and release gate option names are consistent across tasks.

Plan complete and saved to `docs/superpowers/plans/2026-06-26-phase8-4-hot-room-shard-release-gate.md`.
