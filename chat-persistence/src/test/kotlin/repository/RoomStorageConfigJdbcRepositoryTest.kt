package com.chat.persistence.repository

import com.chat.persistence.service.RoomShardConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class RoomStorageConfigJdbcRepositoryTest {
    @Test
    fun `currentShardCount는 room_storage_configs의 current_shard_count를 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), eq(Int::class.java), eq(10L))).thenReturn(16)

        val shardCount = repository.currentShardCount(10L)

        assertEquals(16, shardCount)
    }

    @Test
    fun `currentShardCount는 config row가 없으면 1로 fallback한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), eq(Int::class.java), eq(10L)))
            .thenThrow(EmptyResultDataAccessException(1))

        val shardCount = repository.currentShardCount(10L)

        assertEquals(1, shardCount)
    }

    @Test
    fun `currentShardCount는 1보다 작은 값이면 1로 보정한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), eq(Int::class.java), eq(10L))).thenReturn(0)

        val shardCount = repository.currentShardCount(10L)

        assertEquals(1, shardCount)
    }

    @Test
    fun `shardConfig는 current와 fanout shard count를 함께 반환한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), anyShardConfigRowMapper(), eq(10L)))
            .thenReturn(RoomShardConfig(writeShardCount = 16, fanoutShardCount = 64))

        val config = repository.shardConfig(10L)

        assertEquals(16, config.writeShardCount)
        assertEquals(64, config.fanoutShardCount)
    }

    @Test
    fun `shardConfig는 config row가 없으면 1과 1로 fallback한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), anyShardConfigRowMapper(), eq(10L)))
            .thenThrow(EmptyResultDataAccessException(1))

        val config = repository.shardConfig(10L)

        assertEquals(1, config.writeShardCount)
        assertEquals(1, config.fanoutShardCount)
    }

    @Test
    fun `shardConfig는 1보다 작은 값을 1로 보정한다`() {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val repository = RoomStorageConfigJdbcRepository(jdbcTemplate)
        `when`(jdbcTemplate.queryForObject(anyString(), anyShardConfigRowMapper(), eq(10L)))
            .thenReturn(RoomShardConfig(writeShardCount = 0, fanoutShardCount = -5))

        val config = repository.shardConfig(10L)

        assertEquals(1, config.writeShardCount)
        assertEquals(1, config.fanoutShardCount)
    }

    private fun anyString(): String {
        org.mockito.ArgumentMatchers.anyString()
        return uninitialized()
    }

    private fun anyShardConfigRowMapper(): RowMapper<RoomShardConfig> {
        org.mockito.ArgumentMatchers.any<RowMapper<RoomShardConfig>>()
        return uninitialized()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitialized(): T = null as T
}
