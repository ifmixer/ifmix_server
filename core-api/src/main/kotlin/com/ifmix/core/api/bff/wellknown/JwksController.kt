package com.ifmix.core.api.bff.wellknown

import com.ifmix.core.api.infra.auth.AuthJwtService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * /.well-known 端点：JWKS 公开获取。
 */
@RestController
@RequestMapping("/.well-known")
class JwksController(
    private val jwtService: AuthJwtService,
) {

    @GetMapping("/jwks.json")
    fun jwks(): String = jwtService.jwkSetJson()

    companion object {
        // 简单返回 JSON string，不解析为 Map（避免 Jackson 3 类型推断问题）
        private val mapper = tools.jackson.databind.ObjectMapper()
    }
}
