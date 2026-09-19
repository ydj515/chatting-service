package com.chat.persistence.service

import com.chat.persistence.config.ChatReadDataSourceProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate

class ReadReplicaLagPolicyTest {
    @Test
    fun `disabled read replica does not query either database`() {
        val primary = mock(JdbcTemplate::class.java)
        val replica = mock(JdbcTemplate::class.java)
        val policy = ReadReplicaLagPolicy(replica, ChatReadDataSourceProperties(), primary)
        assertFalse(policy.usePrimaryForLatestHistory())
        assertEquals(0, policy.currentLagMillis())
        verifyNoInteractions(primary, replica)
    }

    @Test
    fun `lag is measured against a primary WAL watermark`() {
        val primary = mock(JdbcTemplate::class.java)
        val replica = mock(JdbcTemplate::class.java)
        `when`(primary.queryForObject("SELECT pg_current_wal_lsn()::text", String::class.java)).thenReturn("0/1234")
        `when`(replica.queryForObject(anyString(), eq(Long::class.javaObjectType), eq("0/1234"))).thenReturn(3000L, 500L, 0L)
        val policy = ReadReplicaLagPolicy(replica, ChatReadDataSourceProperties(enabled = true), primary)
        assertTrue(policy.usePrimaryForLatestHistory())
        assertFalse(policy.usePrimaryForLatestHistory())
        assertEquals(0, policy.currentLagMillis())
    }

    @Test
    fun `missing primary watermark and replica progress fail closed`() {
        val primary = mock(JdbcTemplate::class.java)
        val replica = mock(JdbcTemplate::class.java)
        val policy = ReadReplicaLagPolicy(replica, ChatReadDataSourceProperties(enabled = true), primary)
        assertTrue(policy.usePrimaryForLatestHistory())
        verifyNoInteractions(replica)
        `when`(primary.queryForObject("SELECT pg_current_wal_lsn()::text", String::class.java)).thenReturn("0/1234")
        assertTrue(policy.usePrimaryForLatestHistory())
        `when`(replica.queryForObject(anyString(), eq(Long::class.javaObjectType), eq("0/1234"))).thenThrow(IllegalStateException("replica unavailable"))
        assertTrue(policy.usePrimaryForLatestHistory())
        assertThrows(IllegalStateException::class.java) { policy.currentLagMillis() }
    }
}
