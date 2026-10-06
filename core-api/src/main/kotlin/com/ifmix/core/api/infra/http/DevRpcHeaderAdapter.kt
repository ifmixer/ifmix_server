package com.ifmix.core.api.infra.http

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * dev 态输入适配（proposal「凭证与 meta 分离」§dev 态输入适配，2026-10-06 实施）：
 * 让 Swagger UI / curl 不改 body 也能带上 meta 与凭证——
 *
 * - `Authorization` header（剥 `Bearer ` 前缀，裸 token 也收）→ 凭证底座；
 * - `x-req-meta` header（[RequestMeta] JSON 字符串，**不含凭证**）→ meta 字段底座；
 * - body（`body.meta`）**字段级优先**：字段非空即覆盖 header 底座值（Swagger 里留空的
 *   输入框不会把 Authorize 填的值顶掉）。
 *
 * 合并发生在 [ActionContextFactory.fromRpc] 单点（prod 不注册本 bean，`fromRpc` 行为不变）。
 * 凭证信源收敛为两条：`body.meta.accessToken` > `Authorization` header；`x-req-meta` JSON
 * 里即使带了 `accessToken` 也忽略（契约上该 header 不含凭证，防止把 token 粘进日志友好的 meta）。
 *
 * 仅 local profile 注册（见 application-local.yml：prod 不认 Authorization header——token 进
 * header 即进边缘/访问日志，破坏 wire「凭证对中间层不可见」设计）。
 */
@Component
@Profile("local")
class DevRpcHeaderAdapter(
    private val objectMapper: ObjectMapper,
) {

    /**
     * 返回合并后的 [RequestMeta]（新实例，不改 body 原对象）。header 全缺时原样返回 body meta。
     * `x-req-meta` 非法 JSON 直接抛 [ApiError]（dev fail-fast：格式错就该当场暴露，不做静默降级）。
     */
    fun merge(request: HttpServletRequest, bodyMeta: RequestMeta?): RequestMeta {
        val headerToken = authorizationToken(request)
        val headerMeta = requestMetaHeader(request)

        if (headerToken == null && headerMeta == null) return bodyMeta ?: RequestMeta()

        val body = bodyMeta ?: RequestMeta()
        val base = headerMeta ?: RequestMeta()

        val merged = RequestMeta(
            reqId = pick(body.reqId, base.reqId),
            projectId = pick(body.projectId, base.projectId),
            accessToken = pick(body.accessToken, null), // 凭证单独裁决，见下
            locale = pick(body.locale, base.locale),
            currency = pick(body.currency, base.currency),
            country = pick(body.country, base.country),
            userTz = pick(body.userTz, base.userTz),
            appVersion = pick(body.appVersion, base.appVersion),
            otaVersion = pick(body.otaVersion, base.otaVersion),
            clientPlatform = pick(body.clientPlatform, base.clientPlatform),
            deviceModel = pick(body.deviceModel, base.deviceModel),
            osVersion = pick(body.osVersion, base.osVersion),
        )
        // 凭证：body.meta.accessToken 优先，缺省回落 Authorization header
        return if (merged.accessToken == null && headerToken != null)
            merged.copy(accessToken = headerToken)
        else merged
    }

    /** `Authorization` header → 纯 token；缺失/空白/仅 scheme 无凭证（`Bearer`）/剥前缀后为空 → null。 */
    private fun authorizationToken(request: HttpServletRequest): String? {
        val raw = request.getHeader(HttpHeaders.AUTHORIZATION)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (raw.equals("Bearer", ignoreCase = true)) return null
        if (raw.startsWith("Bearer ", ignoreCase = true)) {
            return raw.substring("Bearer ".length).trim().takeIf { it.isNotEmpty() }
        }
        return raw
    }

    /** `x-req-meta` header → 解析为 [RequestMeta]；header 缺失 → null；非法 JSON → [ApiError]。 */
    private fun requestMetaHeader(request: HttpServletRequest): RequestMeta? {
        val json = request.getHeader(RequestHeaders.DEV_REQ_META)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return try {
            objectMapper.readValue(json, RequestMeta::class.java)
        } catch (_: JacksonException) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid x-req-meta header: not valid RequestMeta JSON")
        }
    }

    /** 字段级合并：body 非空白优先，否则取 header 底座（空白一律当未提供）。 */
    private fun pick(body: String?, base: String?): String? =
        body?.trim()?.takeIf { it.isNotEmpty() } ?: base?.trim()?.takeIf { it.isNotEmpty() }
}
