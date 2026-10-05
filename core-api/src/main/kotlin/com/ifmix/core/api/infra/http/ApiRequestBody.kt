package com.ifmix.core.api.infra.http

import tools.jackson.databind.JsonNode

/**
 * RPC wire v3 信封 body（解密后）。
 *
 * @param meta 请求元信息；缺失视为全空 meta（容错：明文 curl 调试场景）。
 * @param input action 输入 payload；各 controller 用 objectMapper.convertValue 转具体输入类型。
 */
data class ApiRequestBody(
    val meta: RequestMeta?,
    val input: JsonNode? = null,
)
