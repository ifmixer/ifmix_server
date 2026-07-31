package com.ifmix.api.core.infra.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springdoc.core.customizers.GlobalOpenApiCustomizer

/**
 * 让文档里的响应体和运行时真实响应体一致。
 *
 * 运行时 [com.ifmix.api.core.infra.http.EnvelopeResponseAdvice] 会把 controller 返回的裸 DTO
 * 包成 `{ code, msg, data }`，但 springdoc 只能看到 controller 签名上的裸 DTO，
 * 于是生成的 schema 少了一层信封——客户端代码生成出来直接就是错的。
 *
 * 这里在文档生成的最后一步，把每个 2xx 响应 schema 包进 `Envelope{DataType}` 命名 schema，
 * 保证 openapi-generator 之类的工具产出可用的类型。
 *
 * 必须实现 [GlobalOpenApiCustomizer] 而不是 OpenApiCustomizer：
 * 后者只作用于默认（未分组）文档，springdoc 给每个 GroupedOpenApi 组装 customizer 时
 * 只会合入 GlobalOpenApiCustomizer 类型的 bean
 * （见 AbstractMultipleOpenApiResource.afterPropertiesSet），
 * 否则 customer / app / platform 三个分组的文档拿不到信封。
 *
 * 跳过规则与 advice 保持一致：裸 `String` 返回（jwks、webhook）不走信封，因此
 * `type: string` 的响应原样保留。
 */
class EnvelopeSchemaCustomizer : GlobalOpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        val paths = openApi.paths ?: return
        val components = openApi.components ?: Components().also { openApi.components = it }

        for (pathItem in paths.values) {
            for (operation in pathItem.readOperations()) {
                val responses = operation.responses ?: continue
                for ((status, response) in responses) {
                    if (!status.startsWith("2")) continue
                    val content = response.content ?: continue
                    for (mediaType in content.values) {
                        val data = mediaType.schema ?: continue
                        if (skip(data)) continue
                        mediaType.schema = envelopeOf(data, components)
                    }
                }
            }
        }
    }

    /** 已经是信封、或是 advice 不会包装的裸 String，都跳过。 */
    private fun skip(schema: Schema<*>): Boolean {
        val ref = schema.`$ref`
        if (ref != null) return ref.substringAfterLast('/').startsWith(ENVELOPE_PREFIX)
        if (schema.type == "string" && schema.items == null) return true
        val props = schema.properties ?: return false
        return props.containsKey("code") && props.containsKey("msg") && props.containsKey("data")
    }

    private fun envelopeOf(data: Schema<*>, components: Components): Schema<*> {
        val name = dataTypeName(data) ?: return buildEnvelope(data) // 匿名类型只能内联
        val envelopeName = ENVELOPE_PREFIX + name
        if (components.schemas?.containsKey(envelopeName) != true) {
            components.addSchemas(envelopeName, buildEnvelope(data))
        }
        return Schema<Any>().`$ref`("#/components/schemas/$envelopeName")
    }

    private fun buildEnvelope(data: Schema<*>): Schema<*> =
        ObjectSchema()
            .description("统一响应信封。code 为业务码字符串，成功固定 200000。")
            .addProperty("code", StringSchema().example("200000"))
            .addProperty("msg", StringSchema().example("success"))
            .addProperty("data", data)
            .required(listOf("code", "msg"))

    /** 给信封生成一个稳定、可读的名字，便于客户端代码生成。 */
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

    private companion object {
        const val ENVELOPE_PREFIX = "Envelope"
    }
}
