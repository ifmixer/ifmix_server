package com.ifmix.core.api.infra.graphql.scalars

import com.netflix.graphql.dgs.DgsScalar
import graphql.language.StringValue
import graphql.schema.Coercing
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * GraphQL DateTime 标量：序列化为 ISO-8601 UTC 字符串（如 "2026-08-22T04:50:00Z"），
 * 解析只接受 ISO-8601 字符串（RFC 3339：UTC `Z` 或带偏移量；v1.0.6 起 epoch millis 已删，
 * app 未上线无兼容负担，CODING_GUIDE 约定 DateTime 全链路 ISO-8601 字符串）。
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
            is StringValue -> parseString(value.value!!)
            else -> toInstant(value)
        }

    private fun toInstant(v: Any): Instant = when (v) {
        is String -> parseString(v)
        else -> throw IllegalArgumentException("DateTime must be an ISO-8601 string")
    }

    private fun parseString(s: String): Instant =
        try {
            Instant.parse(s)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("DateTime must be an ISO-8601 string: $s")
            }
        }
}
