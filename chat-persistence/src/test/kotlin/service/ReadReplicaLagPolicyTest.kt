package com.chat.persistence.service

import com.chat.persistence.config.ChatReadDataSourceProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

class ReadReplicaLagPolicyTest {

    @Test
    fun `usePrimaryForLatestHistory는 read datasource가 꺼져 있으면 false를 반환한다`() {
        val policy = ReadReplicaLagPolicy(
            messageReadJdbcTemplate = mock(JdbcTemplate::class.java),
            properties = ChatReadDataSourceProperties(enabled = false),
        )

        assertEquals(false, policy.usePrimaryForLatestHistory())
    }

    @Test
    fun `usePrimaryForLatestHistory는 replica lag가 임계치를 넘으면 true를 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        `when`(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long::class.java)))
            .thenReturn(3_000L)
        val policy = ReadReplicaLagPolicy(
            messageReadJdbcTemplate = jdbcTemplate,
            properties = ChatReadDataSourceProperties(
                enabled = true,
                latestHistoryMaxReplicaLag = Duration.ofSeconds(2),
            ),
        )

        assertEquals(true, policy.usePrimaryForLatestHistory())
    }

    @Test
    fun `replica lag 쿼리는 WAL receive와 replay LSN이 같으면 lag를 0으로 계산한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        `when`(jdbcTemplate.queryForObject(captureString(sqlCaptor), org.mockito.ArgumentMatchers.eq(Long::class.java)))
            .thenReturn(0L)
        val policy = ReadReplicaLagPolicy(
            messageReadJdbcTemplate = jdbcTemplate,
            properties = ChatReadDataSourceProperties(enabled = true),
        )

        assertEquals(false, policy.usePrimaryForLatestHistory())
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long::class.java))
        assertTrue(sqlCaptor.value.contains("pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn()"))
    }

    @Test
    fun `usePrimaryForLatestHistory는 lag 측정 실패 시 primary fallback을 선택한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        `when`(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long::class.java)))
            .thenThrow(IllegalStateException("replica unavailable"))
        val policy = ReadReplicaLagPolicy(
            messageReadJdbcTemplate = jdbcTemplate,
            properties = ChatReadDataSourceProperties(enabled = true),
        )

        assertEquals(true, policy.usePrimaryForLatestHistory())
    }

    private fun anyString(): String {
        org.mockito.ArgumentMatchers.anyString()
        return uninitialized()
    }

    private fun captureString(captor: ArgumentCaptor<String>): String {
        captor.capture()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
