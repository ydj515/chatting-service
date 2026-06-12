package com.chat.admin.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin")
class AdminHealthController {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
