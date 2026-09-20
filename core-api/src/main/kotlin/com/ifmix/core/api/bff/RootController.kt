package com.ifmix.core.api.bff

import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 根路径：返回简单 JSON，避免访问 `/` 时 404。
 */
@RestController
class RootController(private val environment: Environment) {

    @GetMapping("/")
    fun root(): Map<String, Any> = mapOf(
        "service" to "core-api",
        "status" to "ok",
        "version" to "1.0.2",
        // 当前激活 profile（SPRING_PROFILES_ACTIVE）；未设时为 "default"
        "env" to environment.activeProfiles.joinToString(",").ifEmpty { "default" },
    )
}
