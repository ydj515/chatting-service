package com.chat.persistence.service

import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.persistence.repository.UserSanctionJdbcRepository
import com.chat.persistence.repository.UserSanctionRecord
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.stream.Stream

class UserSanctionServiceTest {

    @Test
    fun `mute 제재가 있으면 메시지 전송을 거부한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(repository.activeSanctionsForUser(10L, 7L)).thenReturn(
            listOf(sanction(UserSanctionType.MUTE)),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        val exception = assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }

        assertEquals("user is restricted from sending messages", exception.message)
    }

    @Test
    fun `ban 제재가 있으면 메시지 전송을 거부한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(repository.activeSanctionsForUser(10L, 7L)).thenReturn(
            listOf(sanction(UserSanctionType.BAN)),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }
    }

    @Test
    fun `활성 제재가 없으면 통과한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(repository.activeSanctionsForUser(10L, 7L)).thenReturn(emptyList())
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        service.requireAllowedToSend(roomId = 10L, userId = 7L)
    }

    @Test
    fun `캐시에서 반환된 만료 제재는 메시지 전송 차단 대상에서 제외한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeGlobalSanctionsForUser(7L)).thenReturn(emptyList())
        `when`(repository.activeSanctionsForUser(10L, 7L)).thenReturn(
            listOf(sanction(UserSanctionType.MUTE, expiresAt = now.minusSeconds(1))),
        )
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        service.requireAllowedToSend(roomId = 10L, userId = 7L)
    }

    @Test
    fun `global suspend 제재가 있으면 메시지 전송을 거부한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeGlobalSanctionsForUser(7L)).thenReturn(
            listOf(
                sanction(
                    type = UserSanctionType.SUSPEND,
                    scopeType = ModerationScopeType.GLOBAL,
                    roomId = null,
                ),
            ),
        )
        `when`(repository.activeSanctionsForUser(10L, 7L)).thenReturn(emptyList())
        val service = UserSanctionService(repository, Clock.fixed(now, ZoneOffset.UTC), null)

        assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }
    }

    @Test
    fun `global sanction rejection metric은 global scope로 기록한다`() {
        val repository = mock(UserSanctionJdbcRepository::class.java)
        val meterRegistry = SimpleMeterRegistry()
        val now = Instant.parse("2026-06-26T00:00:00Z")
        `when`(repository.activeGlobalSanctionsForUser(7L)).thenReturn(
            listOf(
                sanction(
                    type = UserSanctionType.SUSPEND,
                    scopeType = ModerationScopeType.GLOBAL,
                    roomId = null,
                ),
            ),
        )
        `when`(repository.activeSanctionsForUser(10L, 7L)).thenReturn(emptyList())
        val service = UserSanctionService(
            repository,
            Clock.fixed(now, ZoneOffset.UTC),
            meterRegistryProvider(meterRegistry),
        )

        assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowedToSend(roomId = 10L, userId = 7L)
        }

        val counter = requireNotNull(
            meterRegistry.find("chat.message.moderation.rejected")
                .tag("reason", "suspended")
                .tag("scope", "global")
                .tag("action", "reject")
                .counter(),
        )
        assertEquals(1.0, counter.count())
    }

    private fun sanction(
        type: UserSanctionType,
        expiresAt: Instant? = null,
        scopeType: ModerationScopeType = ModerationScopeType.ROOM,
        roomId: Long? = 10L,
    ): UserSanctionRecord {
        return UserSanctionRecord(
            id = 1L,
            scopeType = scopeType,
            roomId = roomId,
            userId = 7L,
            type = type,
            reason = "spam",
            expiresAt = expiresAt,
            active = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            revokedBy = null,
            revokedAt = null,
        )
    }

    private fun meterRegistryProvider(meterRegistry: MeterRegistry): ObjectProvider<MeterRegistry> {
        return object : ObjectProvider<MeterRegistry> {
            override fun getObject(): MeterRegistry = meterRegistry
            override fun getObject(vararg args: Any?): MeterRegistry = meterRegistry
            override fun getIfAvailable(): MeterRegistry = meterRegistry
            override fun getIfUnique(): MeterRegistry = meterRegistry
            override fun iterator(): MutableIterator<MeterRegistry> = mutableListOf(meterRegistry).iterator()
            override fun stream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
            override fun orderedStream(): Stream<MeterRegistry> = Stream.of(meterRegistry)
        }
    }
}
