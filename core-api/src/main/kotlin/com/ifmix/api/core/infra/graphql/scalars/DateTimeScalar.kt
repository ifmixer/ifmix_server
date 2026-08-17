package com.ifmix.api.core.infra.graphql.scalars

import com.netflix.graphql.dgs.DgsScalar
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.schema.Coercing
import java.time.Instant

/**
 * GraphQL DateTime 标量：序列化为 epoch millis (Long)，
 * 解析时接受数字（Long / IntegerValue）或 ISO 字符串。
 */
@DgsScalar(name = "DateTime")
object DateTimeScalar : Coercing<Instant, Long> {

    override fun serialize(dataFetcherResult: Any): Long {
        @Suppress("UNCHECKED_CAST")
        val instant = dataFetcherResult as Instant
        return instant.toEpochMilli()
    }

    /** 查询变量中的数字值（Jackson 解析为 Integer / Long / Double 等）。 */
    override fun parseValue(value: Any): Instant =
        Instant.ofEpochMilli(toLong(value))

    /** 内联 GraphQL 字面量：IntValue / StringValue。 */
    override fun parseLiteral(value: Any): Instant =
        when (value) {
            is IntValue -> Instant.ofEpochMilli(value.value.toLong())
            is StringValue -> Instant.ofEpochMilli(value.value!!.toLong())
            else -> Instant.ofEpochMilli(toLong(value))
        }

    private fun toLong(v: Any): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLong()
        else -> throw IllegalArgumentException("DateTime must be a number or string")
    }
}
