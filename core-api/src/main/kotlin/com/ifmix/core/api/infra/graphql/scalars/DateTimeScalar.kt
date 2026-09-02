package com.ifmix.core.api.infra.graphql.scalars

import com.netflix.graphql.dgs.DgsScalar
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.schema.Coercing
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * GraphQL DateTime 标量：序列化为 ISO-8601 UTC 字符串（如 "2026-08-22T04:50:00Z"），
 * 解析时接受 ISO-8601 字符串或 epoch millis 数字（兼容旧客户端）。
 */
@DgsScalar(name = "DateTime")
object DateTimeScalar : Coercing<Instant, String> {

    override fun serialize(dataFetcherResult: Any): String {
        val instant = dataFetcherResult as Instant
        return instant.toString()
    }

    override fun parseValue(value: Any): Instant = toInstant(value)

    override fun parseLiteral(value: Any): Instant =
        when (value) {
            is IntValue -> Instant.ofEpochMilli(value.value.toLong())
            is StringValue -> parseString(value.value!!)
            else -> toInstant(value)
        }

    private fun toInstant(v: Any): Instant = when (v) {
        is Number -> Instant.ofEpochMilli(v.toLong())
        is String -> parseString(v)
        else -> throw IllegalArgumentException("DateTime must be ISO-8601 string or epoch millis number")
    }

    private fun parseString(s: String): Instant =
        try {
            Instant.parse(s)
        } catch (_: DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(s).toInstant()
            } catch (_: DateTimeParseException) {
                Instant.ofEpochMilli(s.toLong())
            }
        }
}
