package com.ifmix.api.core.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.GlobalOpenApiCustomizer

/**
 * 全局 OpenAPI 后处理器。职责：
 *
 * 1. **信封包装** — controller 裸返回的 DTO 包成 `{ code, msg, data }` 信封 schema，
 *    与运行时 [com.ifmix.api.core.common.http.EnvelopeResponseAdvice] 行为对齐。
 * 2. **Content-Type 修正** — 2xx 响应从通配符改为 application/json（P0-3）。
 * 3. **ErrorEnvelope + 标准错误响应** — 注册错误信封 schema 并给每个 operation 补
 *    400/401/404/429/500（P0-4）。
 *
 * 必须实现 [GlobalOpenApiCustomizer] 才能作用于所有 GroupedOpenApi 分组。
 */
class EnvelopeSchemaCustomizer : GlobalOpenApiCustomizer {

    override fun customise(openApi: io.swagger.v3.oas.models.OpenAPI) {
        val paths = openApi.paths ?: return
        val components = openApi.components ?: Components().also { openApi.components = it }

        // --- Pass 1: 信封包装 + content-type 修正 ---
        for (pathItem in paths.values) {
            for (operation in pathItem.readOperations()) {
                val responses = operation.responses ?: continue
                for ((status, response) in responses) {
                    if (!status.startsWith("2")) continue
                    val content = response.content ?: continue

                    // P0-3: */* → application/json
                    val wildcardMedia = content.remove("*/*")
                    if (wildcardMedia != null && !content.containsKey("application/json")) {
                        content.addMediaType("application/json", wildcardMedia)
                    }

                    for (mediaType in content.values) {
                        val data = mediaType.schema ?: continue
                        if (skip(data)) continue
                        mediaType.schema = envelopeOf(data, components)
                    }
                }
            }
        }

        // --- Pass 2: ErrorEnvelope schema + 标准错误响应 ---
        registerErrorSchema(components)

        val errorRef = Schema<Any>().`$ref`("#/components/schemas/ErrorEnvelope")
        for (pathItem in paths.values) {
            for (operation in pathItem.readOperations()) {
                val responses = operation.responses ?: continue
                if (!responses.containsKey("400")) {
                    responses.addApiResponse("400", createErrorResponse("参数错误（INVALID_REQUEST）", errorRef))
                }
                if (!responses.containsKey("401")) {
                    responses.addApiResponse("401", createErrorResponse("未鉴权 / token 无效或过期", errorRef))
                }
                if (!responses.containsKey("404")) {
                    responses.addApiResponse("404", createErrorResponse("资源不存在", errorRef))
                }
                if (!responses.containsKey("429")) {
                    val resp = createErrorResponse("限流 / 额度用尽", errorRef)
                    resp.addHeaderObject(
                        "Retry-After",
                        Header()
                            .description("建议客户端等待的秒数（固定窗口剩余时间）")
                            .schema(IntegerSchema()),
                    )
                    responses.addApiResponse("429", resp)
                }
                if (!responses.containsKey("500")) {
                    responses.addApiResponse("500", createErrorResponse("服务端内部错误", errorRef))
                }
            }
        }
    }

    // ==========================================================
    // Envelope wrapping
    // ==========================================================

    private fun skip(schema: Schema<*>): Boolean {
        val ref = schema.`$ref`
        if (ref != null) return ref.substringAfterLast('/').startsWith(ENVELOPE_PREFIX)
        if (schema.type == "string" && schema.items == null) return true
        val props = schema.properties ?: return false
        return props.containsKey("code") && props.containsKey("msg") && props.containsKey("data")
    }

    private fun envelopeOf(data: Schema<*>, components: Components): Schema<*> {
        val name = dataTypeName(data) ?: return buildEnvelope(data)
        val envelopeName = ENVELOPE_PREFIX + name
        if (components.schemas?.containsKey(envelopeName) != true) {
            components.addSchemas(envelopeName, buildEnvelope(data))
        }
        return Schema<Any>().`$ref`("#/components/schemas/$envelopeName")
    }

    private fun buildEnvelope(data: Schema<*>): Schema<*> =
        ObjectSchema()
            .description("统一响应信封。code 为业务码字符串，成功固定 \"200000\"。")
            .addProperty("code", StringSchema().example("200000"))
            .addProperty("msg", StringSchema().example("success"))
            .addProperty("data", data)
            .required(listOf("code", "msg", "data"))

    private fun dataTypeName(schema: Schema<*>): String? {
        schema.`$ref`?.let { return it.substringAfterLast('/') }
        schema.items?.let { item -> return dataTypeName(item)?.let { "${it}List" } }
        return when (schema.type) {
            "string" -> "String"
            "boolean" -> "Boolean"
            "number" -> "Double"
            "integer" -> if (schema.format == "int64") "Long" else "Int"
            else -> null
        }
    }

    // ==========================================================
    // Error schema (P0-4)
    // ==========================================================

    private fun registerErrorSchema(components: Components) {
        val codeSchema = StringSchema().apply {
            description = """
                业务错误码（字符串）。完整取值：
                - "200000" — 成功
                - "400000" — 参数错误 / x-app-id 缺失或格式错误 (INVALID_REQUEST)
                - "400002" — appId 合法但后台未配置对应 AppConfig (APP_CONFIG_MISSING)
                - "401000" — 未鉴权 / token 无效 (UNAUTHORIZED)
                - "401001" — 第三方登录失败 (AUTH_PROVIDER_FAILED)
                - "402000" — IAP 验证失败 (IAP_VERIFY_FAILED)
                - "403000" — 禁止访问 (FORBIDDEN)
                - "404000" — 资源不存在 (NOT_FOUND)
                - "401002" — access token 过期，可用 refresh 重试 (TOKEN_EXPIRED)
                - "401003" — refresh token 失效，需重新登录 (REFRESH_EXPIRED)
                - "429000" — 限流 / 日配额用尽 (RATE_LIMITED)
                - "429001" — 日配额用尽，升级可解锁 (QUOTA_EXCEEDED)
                - "500000" — 服务端错误 (INTERNAL)
                - "503000" — AI 服务不可用 (AI_UNAVAILABLE)
            """.trimIndent()
            example = "400000"
        }

        val errorSchema = ObjectSchema()
            .description(
                "错误响应信封。" +
                "429 场景：`Retry-After` 响应头为首选（固定窗口剩余秒数），" +
                "`data` 不携带 retryAfter（前端优先读响应头即可）。",
            )
            .addProperty("code", codeSchema)
            .addProperty("msg", StringSchema().description("人类可读的错误描述").example("daily limit exceeded"))
            .addProperty("data", Schema<Any>().description("通常为 null。部分错误场景可能携带附加信息。").nullable(true))
            .required(listOf("code", "msg"))

        components.addSchemas("ErrorEnvelope", errorSchema)
    }

    private fun createErrorResponse(description: String, schema: Schema<*>): ApiResponse {
        val mediaType = MediaType().schema(schema)
        val content = Content().addMediaType("application/json", mediaType)
        return ApiResponse()
            .description(description)
            .content(content)
    }

    private companion object {
        const val ENVELOPE_PREFIX = "Envelope"
    }
}
