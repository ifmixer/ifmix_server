package com.ifmix.api.core.infra.graphql.scalars

import com.netflix.graphql.dgs.DgsScalar
import graphql.language.StringValue
import graphql.schema.Coercing
import java.util.UUID

/**
 * GraphQL UUID 标量：序列化为字符串，解析时接受字符串或 StringValue AST 节点。
 */
@DgsScalar(name = "UUID")
object UuidScalar : Coercing<UUID, String> {

    override fun serialize(dataFetcherResult: Any): String {
        @Suppress("UNCHECKED_CAST")
        val uuid = dataFetcherResult as UUID
        return uuid.toString()
    }

    /** 查询变量中传入的原始值（JSON 解析后为 String）。 */
    override fun parseValue(value: Any): UUID =
        UUID.fromString(value.toString())

    /** 内联 GraphQL 字面量（AST StringValue）。 */
    override fun parseLiteral(value: Any): UUID =
        when (value) {
            is StringValue -> UUID.fromString(value.value)
            is String -> UUID.fromString(value)
            else -> throw IllegalArgumentException("UUID must be a string")
        }
}
