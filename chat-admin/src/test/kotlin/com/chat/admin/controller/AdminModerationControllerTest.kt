package com.chat.admin.controller

import com.chat.admin.config.AdminProperties
import com.chat.admin.security.AdminTokenVerifier
import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.dto.ModerationAction
import com.chat.domain.dto.ModerationMatchType
import com.chat.domain.dto.ModerationScopeType
import com.chat.domain.dto.UserSanctionType
import com.chat.domain.service.AdminModerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class AdminModerationControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var service: RecordingAdminModerationService

    @BeforeEach
    fun setUp() {
        service = RecordingAdminModerationService()
        val properties = AdminProperties(token = "local-admin-token", actor = "admin-local")
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                AdminModerationController(
                    adminTokenVerifier = AdminTokenVerifier(properties),
                    adminModerationService = service,
                ),
            )
            .build()
    }

    @Test
    fun `rule 생성은 token을 검증하고 service로 전달한다`() {
        mockMvc.post("/admin/moderation/rules") {
            header("X-Admin-Token", "local-admin-token")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "scopeType": "ROOM",
                  "roomId": 10,
                  "pattern": "blocked",
                  "matchType": "CONTAINS",
                  "action": "REJECT",
                  "reason": "blocked phrase"
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
        }

        assertEquals("admin-local", service.createRuleActor)
        assertEquals(10L, service.createRuleRequest?.roomId)
        assertEquals("blocked", service.createRuleRequest?.pattern)
    }

    @Test
    fun `sanction 생성은 token을 검증하고 service로 전달한다`() {
        mockMvc.post("/admin/moderation/sanctions") {
            header("X-Admin-Token", "local-admin-token")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "scopeType": "ROOM",
                  "roomId": 10,
                  "userId": 7,
                  "type": "MUTE",
                  "reason": "spam"
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(2) }
        }

        assertEquals("admin-local", service.createSanctionActor)
        assertEquals(10L, service.createSanctionRequest?.roomId)
        assertEquals(7L, service.createSanctionRequest?.userId)
        assertEquals(UserSanctionType.MUTE, service.createSanctionRequest?.type)
    }

    @Test
    fun `token이 없으면 moderation API를 401로 거부한다`() {
        mockMvc.get("/admin/moderation/rules")
            .andExpect { status { isUnauthorized() } }
    }

    private class RecordingAdminModerationService : AdminModerationService {
        var createRuleActor: String? = null
        var createRuleRequest: AdminCreateModerationRuleRequest? = null
        var createSanctionActor: String? = null
        var createSanctionRequest: AdminCreateUserSanctionRequest? = null

        override fun listRules(actor: String, roomId: Long?, enabled: Boolean?): List<AdminModerationRuleDto> {
            return emptyList()
        }

        override fun createRule(actor: String, request: AdminCreateModerationRuleRequest): AdminModerationRuleDto {
            createRuleActor = actor
            createRuleRequest = request
            return AdminModerationRuleDto(
                id = 1L,
                scopeType = request.scopeType,
                roomId = request.roomId,
                pattern = request.pattern,
                matchType = request.matchType,
                action = request.action,
                reason = request.reason,
                enabled = true,
                createdBy = actor,
                createdAt = Instant.parse("2026-06-26T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
            )
        }

        override fun updateRule(
            actor: String,
            ruleId: Long,
            request: AdminUpdateModerationRuleRequest,
        ): AdminModerationRuleDto {
            throw UnsupportedOperationException()
        }

        override fun disableRule(actor: String, ruleId: Long): AdminModerationRuleDto {
            throw UnsupportedOperationException()
        }

        override fun listSanctions(
            actor: String,
            roomId: Long?,
            userId: Long?,
            active: Boolean?,
        ): List<AdminUserSanctionDto> {
            return emptyList()
        }

        override fun createSanction(actor: String, request: AdminCreateUserSanctionRequest): AdminUserSanctionDto {
            createSanctionActor = actor
            createSanctionRequest = request
            return AdminUserSanctionDto(
                id = 2L,
                scopeType = request.scopeType,
                roomId = request.roomId,
                userId = request.userId,
                type = request.type,
                reason = request.reason,
                expiresAt = request.expiresAt,
                active = true,
                createdBy = actor,
                createdAt = Instant.parse("2026-06-26T00:00:00Z"),
                revokedBy = null,
                revokedAt = null,
            )
        }

        override fun revokeSanction(actor: String, sanctionId: Long): AdminUserSanctionDto {
            throw UnsupportedOperationException()
        }
    }
}
