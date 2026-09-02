package com.ifmix.core.api.bff

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 根路径：返回简单 JSON，避免访问 `/` 时 404。
 */
@RestController
class RootController {

    @GetMapping("/")
    fun root(): Map<String, Any> = mapOf(
        "service" to "ifmix-core-api",
        "status" to "ok",
    )
}
