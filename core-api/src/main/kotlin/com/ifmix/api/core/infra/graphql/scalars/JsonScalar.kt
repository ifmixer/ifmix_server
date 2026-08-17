package com.ifmix.api.core.infra.graphql.scalars

import com.fasterxml.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.netflix.graphql.dgs.DgsScalar
import graphql.schema.Coercing

private val mapper = jacksonObjectMapper()

/**
 * GraphQL JSON 标量：透传任意合法 JSON 结构。
 * - serialize：原值返回（data fetcher 已产出 Java 对象）。
 * - parseValue：查询变量经 Jackson 解析后已是 Map/List/原生类型，直接返回。
 * - parseLiteral：将 GraphQL AST（ObjectValue / ArrayValue / StringValue 等）递归转为 Java 对象。
 */
@DgsScalar(name = "JSON")
object JsonScalar : Coercing<Any, Any> {

    override fun serialize(dataFetcherResult: Any): Any = dataFetcherResult

    override fun parseValue(value: Any): Any =
        when (value) {
            is Map<*, *> -> value.mapKeys { it.key.toString() }
            is List<*> -> value
            else -> value
        }

    override fun parseLiteral(value: Any): Any =
        when (value) {
            is JsonNode -> mapper.convertValue(value, Any::class.java)
            else -> value
        }
}
