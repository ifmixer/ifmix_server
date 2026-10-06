package com.ifmix.core.api.infra.http

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * OpenAPI 错误响应补全（学习自 ifmix-server-swagger 的 EnvelopeSchemaCustomizer，按本项目现实裁剪）。
 *
 * 成功信封无需处理：controller 返回签名即 `ResponseEntity<Envelope<T>>`，springdoc 按泛型生成。
 * 真正的缺口是**错误路径**——[GlobalExceptionHandler] 运行时统一包装 `ApiError → Envelope`，
 * controller 签名里看不到，R4 客户端从 OpenAPI codegen 时拿不到错误形状。本 customizer 给
 * `/api/…` 下每个 operation 补「通用错误集」响应（[GENERIC_ERRORS]，任何 RPC endpoint 都可能
 * 命中：鉴权/校验/限流/未知异常），模块特有错误码（ATTESTATION_*、IAP、AI_UNAVAILABLE 等）留给
 * 各 endpoint 用 `@ApiResponse` 显式声明，不做全局超集噪音。
 *
 * 信封形状用独立 `ErrorEnvelope` 组件 schema（`data` 恒 null、reqId 可空回显），与成功 Envelope
 * 泛型 schema 解耦——不依赖 springdoc 的 use-fqn 命名去 $ref 泛型产物。
 *
 * ⚠️ KDoc 注释里禁止出现「/api/ 加双星号」的路径通配写法：Kotlin 块注释可嵌套，路径里的斜杠加双星会被当成再开一层注释，吞掉后续代码（本文件实测踩坑）。
 */
@Component
class RpcOpenApiErrorCustomizer : GlobalOpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        val components = openApi.components ?: io.swagger.v3.oas.models.Components().also { openApi.components = it }
        if (components.schemas?.containsKey(ERROR_ENVELOPE_SCHEMA) != true) {
            components.addSchemas(ERROR_ENVELOPE_SCHEMA, errorEnvelopeSchema())
        }
        openApi.paths?.forEach { (path, pathItem) ->
            if (!path.startsWith("/api/")) return@forEach
            pathItem.readOperations()?.forEach { op -> addGenericErrorResponses(op) }
        }
    }

    private fun addGenericErrorResponses(op: Operation) {
        val responses = op.responses ?: ApiResponses().also { op.responses = it }
        GENERIC_ERRORS.forEach { (status, codes) ->
            val key = status.value().toString()
            if (responses[key] != null) return@forEach // 尊重 endpoint 显式声明的响应
            val description = codes.joinToString("；") { "${it.externalCode}=${it.name}" }
            responses.addApiResponse(
                key,
                ApiResponse()
                    .description("统一错误信封（可能码：$description；HTTP status = code 前三位）")
                    .content(
                        Content().addMediaType(
                            org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$ERROR_ENVELOPE_SCHEMA")),
                        ),
                    ),
            )
        }
    }

    private fun errorEnvelopeSchema(): Schema<Any> =
        Schema<Any>()
            .type("object")
            .description("统一错误信封：data 恒为 null；reqId 回显本请求 reqId（缺省服务端生成）；code 前三位 = HTTP status")
            .addProperties("code", StringSchema()._default("400000").description("语义错误码"))
            .addProperties("msg", StringSchema().description("客户端可读错误消息；线上 5xx 统一通用文案"))
            .addProperties("data", ObjectSchema().nullable(true).description("错误路径恒为 null"))
            .addProperties("reqId", StringSchema().nullable(true).description("请求 id（错误路径兜底回显，与日志 rid 一致）"))

    companion object {
        const val ERROR_ENVELOPE_SCHEMA = "ErrorEnvelope"

        /** 通用错误集（按 HTTP status 分组）：/api/ 前缀 RPC endpoint 均可能返回。 */
        val GENERIC_ERRORS: Map<HttpStatus, List<ErrorCode>> = mapOf(
            HttpStatus.BAD_REQUEST to listOf(ErrorCode.INVALID_REQUEST),
            HttpStatus.UNAUTHORIZED to listOf(ErrorCode.UNAUTHORIZED, ErrorCode.TOKEN_EXPIRED),
            HttpStatus.FORBIDDEN to listOf(ErrorCode.FORBIDDEN),
            HttpStatus.NOT_FOUND to listOf(ErrorCode.NOT_FOUND),
            HttpStatus.TOO_MANY_REQUESTS to listOf(ErrorCode.RATE_LIMITED),
            HttpStatus.INTERNAL_SERVER_ERROR to listOf(ErrorCode.INTERNAL),
        )
    }
}

