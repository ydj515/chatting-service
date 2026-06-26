package com.chat.admin.controller

import com.chat.admin.security.AdminTokenVerifier
import com.chat.domain.dto.AdminCreateModerationRuleRequest
import com.chat.domain.dto.AdminCreateUserSanctionRequest
import com.chat.domain.dto.AdminModerationRuleDto
import com.chat.domain.dto.AdminUpdateModerationRuleRequest
import com.chat.domain.dto.AdminUserSanctionDto
import com.chat.domain.service.AdminModerationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/moderation")
class AdminModerationController(
    private val adminTokenVerifier: AdminTokenVerifier,
    private val adminModerationService: AdminModerationService,
) {

    @GetMapping("/rules")
    fun listRules(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestParam(required = false) roomId: Long?,
        @RequestParam(required = false) enabled: Boolean?,
    ): List<AdminModerationRuleDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.listRules(actor, roomId, enabled)
    }

    @PostMapping("/rules")
    fun createRule(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestBody request: AdminCreateModerationRuleRequest,
    ): ResponseEntity<AdminModerationRuleDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(adminModerationService.createRule(actor, request))
    }

    @PatchMapping("/rules/{ruleId}")
    fun updateRule(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable ruleId: Long,
        @RequestBody request: AdminUpdateModerationRuleRequest,
    ): AdminModerationRuleDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.updateRule(actor, ruleId, request)
    }

    @PostMapping("/rules/{ruleId}/disable")
    fun disableRule(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable ruleId: Long,
    ): AdminModerationRuleDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.disableRule(actor, ruleId)
    }

    @GetMapping("/sanctions")
    fun listSanctions(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestParam(required = false) roomId: Long?,
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) active: Boolean?,
    ): List<AdminUserSanctionDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.listSanctions(actor, roomId, userId, active)
    }

    @PostMapping("/sanctions")
    fun createSanction(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @RequestBody request: AdminCreateUserSanctionRequest,
    ): ResponseEntity<AdminUserSanctionDto> {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(adminModerationService.createSanction(actor, request))
    }

    @PostMapping("/sanctions/{sanctionId}/revoke")
    fun revokeSanction(
        @RequestHeader(ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
        @PathVariable sanctionId: Long,
    ): AdminUserSanctionDto {
        val actor = adminTokenVerifier.requireActor(adminToken)
        return adminModerationService.revokeSanction(actor, sanctionId)
    }

    private companion object {
        const val ADMIN_TOKEN_HEADER = "X-Admin-Token"
    }
}
