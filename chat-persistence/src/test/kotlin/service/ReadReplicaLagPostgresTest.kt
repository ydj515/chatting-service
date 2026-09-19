package com.chat.persistence.service

import com.chat.persistence.config.ChatReadDataSourceProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "CHAT_TEST_POSTGRES_URL", matches = ".+")
class ReadReplicaLagPostgresTest {
    @Test
    fun `equal receive and replay positions are stale when primary has advanced`() {
        val connection = DriverManager.getConnection(System.getenv("CHAT_TEST_POSTGRES_URL"), "postgres", System.getenv("CHAT_TEST_POSTGRES_PASSWORD").orEmpty())
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val schema = "lag_probe_${UUID.randomUUID().toString().replace("-", "") }"
        try {
            jdbc.execute("CREATE SCHEMA $schema")
            jdbc.execute("SET search_path TO $schema, pg_catalog")
            jdbc.execute("CREATE TABLE progress (receive_lsn pg_lsn, replay_lsn pg_lsn, replayed_at timestamptz)")
            jdbc.execute("INSERT INTO progress VALUES ('0/100', '0/100', clock_timestamp() - interval '60 seconds')")
            // Simulate standby functions on this private connection, while PostgreSQL evaluates the actual routing SQL.
            jdbc.execute("CREATE FUNCTION $schema.pg_is_in_recovery() RETURNS boolean LANGUAGE sql AS 'SELECT true'")
            jdbc.execute("CREATE FUNCTION $schema.pg_last_wal_receive_lsn() RETURNS pg_lsn LANGUAGE sql AS 'SELECT receive_lsn FROM $schema.progress'")
            jdbc.execute("CREATE FUNCTION $schema.pg_last_wal_replay_lsn() RETURNS pg_lsn LANGUAGE sql AS 'SELECT replay_lsn FROM $schema.progress'")
            jdbc.execute("CREATE FUNCTION $schema.pg_last_xact_replay_timestamp() RETURNS timestamptz LANGUAGE sql AS 'SELECT replayed_at FROM $schema.progress'")
            val primary = mock(JdbcTemplate::class.java)
            `when`(primary.queryForObject("SELECT pg_current_wal_lsn()::text", String::class.java)).thenReturn("0/200")
            val policy = ReadReplicaLagPolicy(jdbc, ChatReadDataSourceProperties(enabled = true), primary)
            assertTrue(policy.currentLagMillis() >= 59000)
            assertTrue(policy.usePrimaryForLatestHistory())

            jdbc.execute("UPDATE progress SET receive_lsn = '0/200', replay_lsn = '0/200'")
            assertEquals(0L, policy.currentLagMillis())
            assertFalse(policy.usePrimaryForLatestHistory())

            jdbc.execute("UPDATE progress SET replay_lsn = NULL, replayed_at = NULL")
            assertTrue(policy.usePrimaryForLatestHistory())
            jdbc.execute("UPDATE progress SET replay_lsn = '0/100'")
            assertTrue(policy.usePrimaryForLatestHistory())
        } finally {
            jdbc.execute("SET search_path TO pg_catalog")
            jdbc.execute("DROP SCHEMA $schema CASCADE")
            source.destroy()
        }
    }
}
