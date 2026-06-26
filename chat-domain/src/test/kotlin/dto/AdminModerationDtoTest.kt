package com.chat.domain.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminModerationDtoTest {

    @Test
    fun `moderation rule 생성 요청은 contains reject를 기본값으로 사용한다`() {
        val request = AdminCreateModerationRuleRequest(
            scopeType = ModerationScopeType.GLOBAL,
            pattern = "blocked",
        )

        assertNull(request.roomId)
        assertEquals(ModerationMatchType.CONTAINS, request.matchType)
        assertEquals(ModerationAction.REJECT, request.action)
        assertNull(request.reason)
    }

    @Test
    fun `user sanction 생성 요청은 만료와 사유를 선택값으로 둔다`() {
        val request = AdminCreateUserSanctionRequest(
            scopeType = ModerationScopeType.ROOM,
            roomId = 10L,
            userId = 7L,
            type = UserSanctionType.MUTE,
        )

        assertEquals(ModerationScopeType.ROOM, request.scopeType)
        assertEquals(10L, request.roomId)
        assertEquals(7L, request.userId)
        assertEquals(UserSanctionType.MUTE, request.type)
        assertNull(request.reason)
        assertNull(request.expiresAt)
    }

    @Test
    fun `moderation rule 응답은 생성자와 갱신 시각을 포함한다`() {
        val createdAt = Instant.parse("2026-06-26T00:00:00Z")
        val updatedAt = Instant.parse("2026-06-26T00:01:00Z")

        val dto = AdminModerationRuleDto(
            id = 1L,
            scopeType = ModerationScopeType.GLOBAL,
            roomId = null,
            pattern = "blocked",
            matchType = ModerationMatchType.CONTAINS,
            action = ModerationAction.REJECT,
            reason = "policy",
            enabled = true,
            createdBy = "admin",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        assertEquals("admin", dto.createdBy)
        assertEquals(createdAt, dto.createdAt)
        assertEquals(updatedAt, dto.updatedAt)
    }
}
