package com.chat.persistence.service

import com.chat.domain.service.SessionControlPublisher
import com.chat.domain.service.SessionTokenRevocationStore
import com.chat.persistence.config.SanctionCacheRetryProperties
import com.chat.persistence.repository.SessionRevocationJobRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
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

class SuspendedSessionRevokerTest {
    @Test
    fun `sanction rollback removes revocation job without sending control events`() {
        val f = Fixture()
        f.transaction.executeWithoutResult { status ->
            f.revoker().revokeAfterCommit(7)
            assertEquals(1, f.pending())
            verifyNoInteractions(f.store, f.publisher)
            status.setRollbackOnly()
        }
        assertEquals(0, f.pending())
        verifyNoInteractions(f.store, f.publisher)
    }

    @Test
    fun `failed Redis revocation survives restart and preserves original cutoff`() {
        val f = Fixture()
        val cutoff = f.clock.instant()
        doThrow(RedisConnectionFailureException("offline")).`when`(f.store).revokeUserTokens(7, cutoff)
        f.transaction.executeWithoutResult { f.revoker().revokeAfterCommit(7) }
        assertEquals(1, f.pending())
        verify(f.publisher).forceLogoutUser(7, "suspended")
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            null
        }.`when`(f.store).revokeUserTokens(7, cutoff)
        val restarted = f.revoker()
        assertEquals(0, restarted.retryPending())
        f.clock.advance(6)
        assertEquals(1, restarted.retryPending())
        verify(f.store, times(2)).revokeUserTokens(7, cutoff)
        assertEquals(0, f.pending())
    }

    @Test
    fun `failed control publication retries even after token revocation succeeds`() {
        val f = Fixture()
        doThrow(RedisConnectionFailureException("offline")).`when`(f.publisher).forceLogoutUser(7, "suspended")
        f.transaction.executeWithoutResult { f.revoker().revokeAfterCommit(7) }
        assertEquals(1, f.pending())
        doNothing().`when`(f.publisher).forceLogoutUser(7, "suspended")
        f.clock.advance(6)
        assertEquals(1, f.revoker().retryPending())
        assertEquals(0, f.pending())
    }

    @Test
    fun `worker recovers committed job without callback and stale owner cannot complete a new lease`() {
        val f = Fixture()
        f.repository.enqueue("job", 7, f.clock.instant())
        val old = requireNotNull(f.transaction.execute { f.repository.claim("job", "old", f.clock.instant(), f.clock.instant().plusSeconds(30)) })
        assertEquals(0, f.revoker().retryPending())
        f.clock.advance(31)
        val current = requireNotNull(f.transaction.execute { f.repository.claim("job", "current", f.clock.instant(), f.clock.instant().plusSeconds(30)) })
        f.repository.complete(old)
        assertEquals(1, f.pending())
        f.repository.reschedule(old, f.clock.instant())
        assertEquals(0, f.revoker().retryPending())
        f.clock.advance(31)
        assertEquals(1, f.revoker().retryPending())
        f.repository.complete(current)
        assertEquals(0, f.pending())
    }

    @Test
    fun `outbox write failure rolls back the domain write`() {
        val f = Fixture()
        f.jdbc.execute("CREATE TABLE domain_changes (id INT PRIMARY KEY)")
        f.jdbc.execute("ALTER TABLE session_revocation_jobs ADD CONSTRAINT reject_job CHECK (user_id < 0)")
        assertThrows(org.springframework.dao.DataAccessException::class.java) {
            f.transaction.executeWithoutResult {
                f.jdbc.update("INSERT INTO domain_changes VALUES (1)")
                f.revoker().revokeAfterCommit(7)
            }
        }
        assertEquals(0, requireNotNull(f.jdbc.queryForObject("SELECT count(*) FROM domain_changes", Int::class.java)))
        verifyNoInteractions(f.store, f.publisher)
    }

    private class Fixture {
        val clock = MutableClock()
        val store: SessionTokenRevocationStore = mock(SessionTokenRevocationStore::class.java)
        val publisher: SessionControlPublisher = mock(SessionControlPublisher::class.java)
        val jdbc = JdbcTemplate(dataSource())
        private val manager = DataSourceTransactionManager(requireNotNull(jdbc.dataSource))
        val transaction = TransactionTemplate(manager)
        val repository = SessionRevocationJobRepository(jdbc)

        init {
            Files.readString(Path.of("../infra/postgres/session-revocation-jobs.sql")).split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
        }

        fun revoker() = SuspendedSessionRevoker(repository, store, publisher, manager, clock, SanctionCacheRetryProperties())

        fun pending(): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM session_revocation_jobs", Int::class.java))
    }

    private class MutableClock : Clock() {
        private var now = Instant.parse("2026-09-20T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private companion object {
        fun dataSource(): DriverManagerDataSource {
            val url = System.getenv("CHAT_TEST_POSTGRES_URL")
                ?: return DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "")
            val password = System.getenv("CHAT_TEST_POSTGRES_PASSWORD").orEmpty()
            val schema = "revocation_${UUID.randomUUID().toString().replace("-", "")}"
            JdbcTemplate(DriverManagerDataSource(url, "postgres", password)).execute("CREATE SCHEMA $schema")
            return DriverManagerDataSource("$url${if ('?' in url) '&' else '?'}currentSchema=$schema", "postgres", password)
        }
    }
}
