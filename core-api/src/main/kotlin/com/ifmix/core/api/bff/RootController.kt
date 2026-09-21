package com.ifmix.core.api.bff

import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 探活端点：
 *  - `GET /`            → 纯文本 `works`（最轻，根路径探活）
 *  - `GET /core/health` → JSON（service/status/version/env），供 Cloudflare tunnel / 监控
 *
 * health 的 map 在构造时预构建一次（env 启动即固定），不每请求重建。
 */
@RestController
class RootController(environment: Environment) {

    // 预构建：env 启动即固定，整个 map 只算一次
    private val healthInfo: Map<String, Any> = mapOf(
        "service" to "core-api",
        "status" to "ok",
        "version" to "1.0.3",
        // 当前激活 profile（SPRING_PROFILES_ACTIVE）；未设时为 "default"
        "env" to environment.activeProfiles.joinToString(",").ifEmpty { "default" },
    )

    @GetMapping("/")
    fun root(): String = "works"

    @GetMapping("/core/health")
    fun health(): Map<String, Any> = healthInfo
}
