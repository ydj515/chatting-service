package com.chat.persistence.service

import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.exception.MessageModerationRejectedException
import com.chat.domain.model.MessageType
import com.chat.persistence.repository.ModerationRuleJdbcRepository
import com.chat.persistence.repository.ModerationRuleRecord
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.stream.Stream

class MessageModerationServiceTest {

    @Test
    fun `global contains rule과 매칭되면 moderation rejected 예외와 metric을 남긴다`() {
        val repository = mock(ModerationRuleJdbcRepository::class.java)
        `when`(repository.activeRulesForRoom(10L)).thenReturn(
            listOf(rule(scopeType = ModerationScopeType.GLOBAL, roomId = null, pattern = "blocked")),
        )
        val meterRegistry = SimpleMeterRegistry()
        val service = MessageModerationService(repository, meterRegistryProvider(meterRegistry))

        val exception = assertThrows(MessageModerationRejectedException::class.java) {
            service.requireAllowed(
                roomId = 10L,
                senderId = 7L,
                content = "this is BLOCKED text",
                messageType = MessageType.TEXT,
            )
        }

        assertEquals("message blocked by moderation policy", exception.message)
        assertEquals(
            1.0,
            meterRegistry.counter(
                "chat.message.moderation.rejected",
                "reason",
                "blocked_word",
                "scope",
                "global",
                "action",
                "reject",
            ).count(),
        )
    }

    @Test
    fun `room contains rule과 매칭되지 않으면 통과한다`() {
        val repository = mock(ModerationRuleJdbcRepository::class.java)
        `when`(repository.activeRulesForRoom(10L)).thenReturn(
            listOf(rule(scopeType = ModerationScopeType.ROOM, roomId = 10L, pattern = "blocked")),
        )
        val service = MessageModerationService(repository, null)

        service.requireAllowed(
            roomId = 10L,
            senderId = 7L,
            content = "clean text",
            messageType = MessageType.TEXT,
        )
    }

    @Test
    fun `text 메시지가 아니면 moderation rule 조회 없이 통과한다`() {
        val repository = mock(ModerationRuleJdbcRepository::class.java)
        val service = MessageModerationService(repository, null)

        service.requireAllowed(
            roomId = 10L,
            senderId = 7L,
            content = "blocked",
            messageType = MessageType.SYSTEM,
        )

        verifyNoInteractions(repository)
    }

    private fun rule(
        scopeType: ModerationScopeType,
        roomId: Long?,
        pattern: String,
    ): ModerationRuleRecord {
        return ModerationRuleRecord(
            id = 1L,
            scopeType = scopeType,
            roomId = roomId,
            pattern = pattern,
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "blocked phrase",
            enabled = true,
            createdBy = "admin-local",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
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
