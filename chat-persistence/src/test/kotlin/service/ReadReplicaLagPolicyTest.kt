package com.chat.persistence.service

import com.chat.persistence.config.ChatReadDataSourceProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
