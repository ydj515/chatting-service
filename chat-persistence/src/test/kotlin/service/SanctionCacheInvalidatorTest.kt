package com.chat.persistence.service

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.persistence.config.SanctionCacheRetryProperties
import com.chat.persistence.repository.SanctionCacheInvalidationRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SanctionCacheInvalidatorTest {
    @ParameterizedTest
    @EnumSource(ModerationScopeType::class)
    fun `invalidation becomes visible only after commit`(scope: ModerationScopeType) {
        val f = Fixture()
        val record = record(scope)
        val key = if (scope == ModerationScopeType.GLOBAL) "global:7" else "10:7"
        f.cache.put(key, "old")
        f.transaction.executeWithoutResult {
            f.invalidator.enqueue(record)
            assertEquals(1, f.pending())
            assertNotNull(f.cache.get(key))
            assertTrue(f.cache.evictions.isEmpty())
        }
        assertNull(f.cache.get(key))
        assertEquals(listOf(key), f.cache.evictions.toList())
        assertEquals(0, f.pending())
    }

    @Test
    fun `rollback removes both domain write and invalidation job`() {
        val f = Fixture()
        f.transaction.executeWithoutResult { status ->
            f.jdbc.update("INSERT INTO domain_changes VALUES (1)")
            f.invalidator.enqueue(record())
            status.setRollbackOnly()
        }
        assertEquals(0, requireNotNull(f.jdbc.queryForObject("SELECT count(*) FROM domain_changes", Int::class.java)))
        assertEquals(0, f.pending())
        assertTrue(f.cache.evictions.isEmpty())
    }

    @Test
    fun `outbox insertion failure rolls back the domain write`() {
        val f = Fixture()
        f.jdbc.execute("ALTER TABLE sanction_cache_invalidations ADD CONSTRAINT reject_job CHECK (cache_key = 'invalid')")
        assertThrows(DataAccessException::class.java) {
            f.transaction.executeWithoutResult {
                f.jdbc.update("INSERT INTO domain_changes VALUES (1)")
                f.invalidator.enqueue(record())
            }
        }
        assertEquals(0, requireNotNull(f.jdbc.queryForObject("SELECT count(*) FROM domain_changes", Int::class.java)))
        assertTrue(f.cache.evictions.isEmpty())
    }

    @Test
    fun `failed eviction survives restart and observes retry delay`() {
        val f = Fixture()
        f.cache.unavailable = true
        f.transaction.executeWithoutResult { f.invalidator.enqueue(record()) }
        assertEquals(1, f.pending())
        assertEquals(1, requireNotNull(f.jdbc.queryForObject("SELECT attempts FROM sanction_cache_invalidations", Int::class.java)))
        f.cache.unavailable = false
        val restarted = f.newInvalidator()
        assertEquals(0, restarted.retryPending())
        f.clock.advance(5_000)
        assertEquals(1, restarted.retryPending())
        assertEquals(0, f.pending())
    }

    @Test
    fun `worker recovers committed job when immediate callback never ran`() {
        val f = Fixture()
        f.transaction.executeWithoutResult { f.repository.enqueue("job", "10:7", f.clock.instant()) }
        f.cache.observe = { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()) }
        assertEquals(1, f.newInvalidator().retryPending())
        assertEquals(0, f.pending())
    }

    @Test
    fun `expired lease is recoverable and stale owner cannot acknowledge new claim`() {
        val f = Fixture()
        f.repository.enqueue("job", "10:7", f.clock.instant())
        val old = requireNotNull(f.transaction.execute { f.repository.claim("job", "old", f.clock.instant(), f.clock.instant().plusSeconds(30)) })
        assertEquals(0, f.invalidator.retryPending())
        f.clock.advance(31_000)
        val current = requireNotNull(f.transaction.execute { f.repository.claim("job", "current", f.clock.instant(), f.clock.instant().plusSeconds(30)) })
        f.transaction.executeWithoutResult { f.repository.complete(old) }
        assertEquals(1, f.pending())
        f.transaction.executeWithoutResult { f.repository.reschedule(old, f.clock.instant()) }
        assertEquals(0, f.invalidator.retryPending())
        f.transaction.executeWithoutResult { f.repository.complete(current) }
        assertEquals(0, f.pending())
    }

    @Test
    fun `two workers cannot own the same live lease`() {
        val f = Fixture()
        f.repository.enqueue("job", "10:7", f.clock.instant())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.cache.observe = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Int> { f.invalidator.retryPending() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(0, f.newInvalidator().retryPending())
            release.countDown()
            assertEquals(1, first.get(5, TimeUnit.SECONDS))
            assertEquals(1, f.cache.evictions.size)
            assertEquals(0, f.pending())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `missing cache retains the invalidation and backoff is capped`() {
        val f = Fixture()
        f.cacheManager.setCaches(emptyList())
        f.cacheManager.afterPropertiesSet()
        f.repository.enqueue("job", "10:7", f.clock.instant())
        assertEquals(0, f.invalidator.retryPending())
        assertEquals(1, f.pending())
        val properties = SanctionCacheRetryProperties()
        assertEquals(5_000, properties.retryDelay(0))
        assertEquals(10_000, properties.retryDelay(1))
        assertEquals(300_000, properties.retryDelay(Int.MAX_VALUE))
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_TEST_POSTGRES_URL", matches = ".+")
    fun `real sanction and audit writes rollback together and cache failure does not block suspension`() {
        val f = Fixture()
        val ddl = Files.readString(Path.of("../infra/postgres/message-partitions.sql"))
        listOf("admin_audit_logs", "user_sanctions").forEach { table ->
            val start = ddl.indexOf("CREATE TABLE IF NOT EXISTS $table (")
            f.jdbc.execute(ddl.substring(start, ddl.indexOf("\n);", start) + 3))
        }
        val tokens = org.mockito.Mockito.mock(com.chat.domain.service.SessionTokenRevocationStore::class.java)
        val logout = org.mockito.Mockito.mock(com.chat.domain.service.SessionControlPublisher::class.java)
        val service = AdminModerationServiceImpl(
            org.mockito.Mockito.mock(com.chat.persistence.repository.ModerationRuleJdbcRepository::class.java),
            com.chat.persistence.repository.UserSanctionJdbcRepository(f.jdbc),
            AdminAuditRecorder(com.chat.persistence.repository.AdminAuditLogRepository(f.jdbc), com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules()),
            SuspendedSessionRevoker(com.chat.persistence.repository.SessionRevocationJobRepository(f.jdbc), tokens, logout, f.transactionManager, f.clock, SanctionCacheRetryProperties()), f.clock, f.invalidator,
        )
        val request = com.chat.domain.dto.AdminCreateUserSanctionRequest(scopeType = ModerationScopeType.GLOBAL, userId = 7, type = UserSanctionType.SUSPEND)
        f.transaction.executeWithoutResult { status ->
            service.createSanction("admin", request)
            assertEquals(1, f.pending())
            status.setRollbackOnly()
        }
        assertEquals(0, requireNotNull(f.jdbc.queryForObject("SELECT count(*) FROM user_sanctions", Int::class.java)))
        assertEquals(0, requireNotNull(f.jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs", Int::class.java)))
        assertEquals(0, f.pending())
        org.mockito.Mockito.verifyNoInteractions(tokens, logout)
        f.cache.unavailable = true
        val sanction = requireNotNull(f.transaction.execute { service.createSanction("admin", request) })
        assertEquals(1, f.pending())
        org.mockito.Mockito.verify(tokens).revokeUserTokens(7, f.clock.instant())
        org.mockito.Mockito.verify(logout).forceLogoutUser(7, "suspended")
        f.cache.unavailable = false
        f.clock.advance(5_000)
        assertEquals(1, f.newInvalidator().retryPending())
        org.mockito.Mockito.verifyNoMoreInteractions(tokens, logout)
        f.transaction.executeWithoutResult { service.revokeSanction("admin", sanction.id) }
        assertEquals(false, f.jdbc.queryForObject("SELECT active FROM user_sanctions WHERE id = ?", Boolean::class.java, sanction.id))
        assertEquals(0, f.pending())
        assertEquals(listOf("global:7", "global:7"), f.cache.evictions.toList())
    }

    private fun record(scope: ModerationScopeType = ModerationScopeType.ROOM): UserSanctionRecord = UserSanctionRecord(
        id = 1, scopeType = scope, roomId = if (scope == ModerationScopeType.ROOM) 10 else null,
        userId = 7, type = if (scope == ModerationScopeType.ROOM) UserSanctionType.MUTE else UserSanctionType.SUSPEND,
        reason = null, expiresAt = null, active = true, createdBy = "admin", createdAt = Instant.EPOCH, revokedBy = null, revokedAt = null,
    )

    private class Fixture {
        val clock = MutableClock()
        private val dataSource = testDataSource()
        val jdbc = JdbcTemplate(dataSource)
        val transactionManager = DataSourceTransactionManager(dataSource)
        val transaction = TransactionTemplate(transactionManager)
        val repository = SanctionCacheInvalidationRepository(jdbc)
        val cache = FaultInjectingCache()
        val cacheManager = SimpleCacheManager().apply {
            setCaches(listOf(cache))
            afterPropertiesSet()
        }
        val invalidator = newInvalidator()

        init {
            Files.readString(Path.of("../infra/postgres/sanction-cache-invalidation.sql")).split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
            Files.readString(Path.of("../infra/postgres/session-revocation-jobs.sql")).split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
            jdbc.execute("CREATE TABLE domain_changes (id INT PRIMARY KEY)")
        }

        fun newInvalidator(): SanctionCacheInvalidator = SanctionCacheInvalidator(repository, cacheManager, transactionManager, clock, SanctionCacheRetryProperties())

        fun pending(): Int = jdbc.queryForObject("SELECT count(*) FROM sanction_cache_invalidations", Int::class.java) ?: 0
    }

    private class MutableClock : Clock() {
        private var now = Instant.parse("2026-09-20T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(millis: Long) {
            now = now.plusMillis(millis)
        }
    }

    private class FaultInjectingCache : ConcurrentMapCache("userSanctions") {
        var unavailable = false
        var observe: () -> Unit = {}
        val evictions = ConcurrentLinkedQueue<Any>()

        override fun evict(key: Any) {
            observe()
            if (unavailable) throw RedisConnectionFailureException("unavailable")
            super.evict(key)
            evictions.add(key)
        }
    }

    private companion object {
        fun testDataSource(): DriverManagerDataSource {
            val postgresUrl = System.getenv("CHAT_TEST_POSTGRES_URL")
            if (postgresUrl == null) return DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "")
            val password = System.getenv("CHAT_TEST_POSTGRES_PASSWORD").orEmpty()
            val schema = "cache_retry_${UUID.randomUUID().toString().replace("-", "")}"
            JdbcTemplate(DriverManagerDataSource(postgresUrl, "postgres", password)).execute("CREATE SCHEMA $schema")
            val separator = if ('?' in postgresUrl) "&" else "?"
            return DriverManagerDataSource("$postgresUrl${separator}currentSchema=$schema", "postgres", password)
        }
    }
}
