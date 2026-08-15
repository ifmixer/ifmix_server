package com.ifmix.api.core.graphql.common.scalar

import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import java.time.Instant

/**
 * GraphQL DateTime scalar：映射到 Java [Instant]，序列化为 ISO-8601 字符串。
 *
 * DGS extended scalars 自带 DateTime（基于 OffsetDateTime），与本项目使用的 Instant 冲突；
 * 此处提供自定义实现并覆盖。
 */
@Suppress("OVERRIDE_DEPRECATION")
class DateTimeScalar : Coercing<Instant, String> {

    override fun serialize(dataFetcherResult: Any): String {
        return when (dataFetcherResult) {
            is Instant -> dataFetcherResult.toString()
            else -> throw CoercingSerializeException("Expected Instant but got ${dataFetcherResult::class.simpleName}")
        }
    }

    override fun parseValue(input: Any): Instant {
        return when (input) {
            is String -> Instant.parse(input)
            else -> throw CoercingParseValueException("Expected String but got ${input::class.simpleName}")
        }
    }

    override fun parseLiteral(input: Any): Instant {
        return when (input) {
            is StringValue -> Instant.parse(input.value)
            else -> throw CoercingParseLiteralException("Expected StringValue but got ${input::class.simpleName}")
        }
    }
}
