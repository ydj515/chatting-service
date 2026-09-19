package com.chat.persistence.service

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.model.User
import com.chat.persistence.config.SanctionCacheRetryProperties
import com.chat.persistence.repository.SanctionCacheInvalidationRepository
import com.chat.persistence.repository.UserRepository
import com.chat.persistence.repository.UserSanctionRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.transaction.TestTransaction
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

@DataJpaTest(properties = ["spring.datasource.generate-unique-name=true"])
@ContextConfiguration(classes = [ChatServiceImplCursorPaginationTest.JpaTestConfig::class])
class SanctionCacheJpaTransactionTest {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var userRepository: UserRepository

    @Test
    fun `JPA and primary JDBC outbox share the same rollback boundary`() {
        val jdbc = JdbcTemplate(dataSource)
        Files.readString(Path.of("../infra/postgres/sanction-cache-invalidation.sql")).split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
        val cacheManager = ConcurrentMapCacheManager("userSanctions")
        val cache = requireNotNull(cacheManager.getCache("userSanctions"))
        cache.put("10:7", "original")
        val invalidator = SanctionCacheInvalidator(SanctionCacheInvalidationRepository(jdbc), cacheManager, transactionManager, Clock.systemUTC(), SanctionCacheRetryProperties())
        val user = userRepository.saveAndFlush(User(username = "outbox-rollback", password = "test", displayName = "Test"))
        invalidator.enqueue(UserSanctionRecord(1, ModerationScopeType.ROOM, 10, 7, UserSanctionType.MUTE, null, null, true, "admin", Instant.now(), null, null))
        assertEquals(1, requireNotNull(jdbc.queryForObject("SELECT count(*) FROM sanction_cache_invalidations", Int::class.java)))
        TestTransaction.flagForRollback()
        TestTransaction.end()
        assertFalse(userRepository.existsById(user.id))
        assertEquals(0, requireNotNull(jdbc.queryForObject("SELECT count(*) FROM sanction_cache_invalidations", Int::class.java)))
        assertEquals("original", cache.get("10:7")?.get())
    }
}
